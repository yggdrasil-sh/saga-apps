"""2.5 Chat-Standard (docs/APP-API-CHAT.md 6a, 8, 9 im Server-Repo) - reine Logik (hlidskjalf/chatstd.py): Feature-Gating,
Rechte aus can_*, Formatierung mit denselben Vektoren wie static/chat-fmt.js (tests/vectors25/chat_fmt.json, erzeugt aus der
Web-Datei) inkl. Markup-Injection, Pango-Markup gueltig, Erwaehnungs-Token, Systemzeilen perm/admin, Stummschalt-Anzeige,
Linkvorschau, Sperren/Melden, Weiterleiten, Chat-Entwuerfe."""
import json
import os
import re
import time

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
ALLF = {"pagechatmembers1", "groupinfo2", "moddel1", "msgpin1", "block2", "report1", "mute2", "chatpin1", "markunread1",
        "chathide1", "chatmention1", "chatfmt1", "unfurl1", "chatmedia1"}
VEC = json.load(open(os.path.join(HERE, "vectors25", "chat_fmt.json"), encoding="utf-8"))
H1, H2, HM = "~" + "a" * 16, "~" + "b" * 16 + "@x.example", "~" + "c" * 16


# --- Feature-Gating und Rechte -----------------------------------------------------------------------------------
def test_gating_without_features():
    from hlidskjalf.chatstd import can_add_members, page_member_action, rights
    c = {"kind": "free", "can_info2": True, "can_edit_info": True, "can_perms": True, "can_pin": True, "can_moderate": True,
         "can_send": False, "can_hide": True}
    r = rights(c, set())
    assert not any(v for k, v in r.items() if k != "send") and r["send"]          # aelterer Server: nichts davon, Senden wie bisher
    page = {"kind": "page", "can_manage": True, "members": [{"username": "x", "removable": True}]}
    assert not can_add_members(page, set()) and page_member_action(page, page["members"][0], set()) is None
    r = rights(c, ALLF)
    assert r["info2"] and r["perms"] and r["pin"] and r["moderate"] and not r["send"] and r["hide"] and r["fmt"]


def test_rights_from_fields():
    from hlidskjalf.chatstd import rights
    base = {"kind": "free", "perm_info": "admins", "perm_send": "admins", "can_send": True, "can_edit_info": False,
            "can_info2": False, "can_perms": False, "can_pin": False}
    r = rights(base, ALLF)
    assert not r["edit_info"] and not r["perms"] and r["show_perms"] and not r["pin"] and r["send"]
    assert rights(dict(base, readonly=True, can_perms=True), ALLF)["perms"] is False
    assert rights({"kind": "group", "can_perms": True}, ALLF)["perms"] is False
    assert rights({"kind": "dm", "can_hide": False}, ALLF)["hide"] is False
    assert rights({"kind": "page", "can_hide": True}, ALLF)["hide"] is True


def test_page_members():
    from hlidskjalf.chatstd import can_add_members, members_hint, page_member_action
    c = {"kind": "page", "can_manage": True, "my_handle": "~me@h",
         "members": [{"username": "me", "me": True, "via": "owner"}, {"username": "a", "via": "share", "removable": True},
                     {"username": "b", "via": "group", "removable": False}, {"username": "c", "via": "parent"},
                     {"username": "d", "via": None}]}
    me, a, b, cc, d = c["members"]
    assert can_add_members(c, ALLF)
    assert page_member_action(c, me, ALLF) is None
    assert page_member_action(c, a, ALLF) == ("remove", None)
    assert page_member_action(c, b, ALLF) == ("off", "Access through a group – remove them there.")
    assert page_member_action(c, cc, ALLF)[1] == "Access through a parent page – revoke the share there."
    assert page_member_action(c, d, ALLF)[1] == "Access not only through this page – check the share dialog."
    ro = dict(c, can_manage=False)
    assert not can_add_members(ro, ALLF) and page_member_action(ro, a, ALLF) is None
    assert members_hint(c, ALLF) == "Adding people shares this page with them. Removing revokes the share."
    assert members_hint(ro, ALLF).startswith("Members are everyone")
    assert members_hint({"kind": "group"}, ALLF) == "Members come from the user group – manage them there."


# --- Formatierung: Vektoren aus static/chat-fmt.js ------------------------------------------------------------------
@pytest.mark.parametrize("v", VEC, ids=[repr(v["text"])[:40] for v in VEC])
def test_fmt_vectors(v):
    from hlidskjalf.chatstd import html, parse, strip
    assert html(parse(v["text"])) == v["html"]
    assert strip(v["text"]) == v["strip"]


def _pango_ok(mk):
    import gi
    gi.require_version("Pango", "1.0")
    from gi.repository import Pango
    mk2 = re.sub(r'<a href="[^"]*">', "<span>", mk).replace("</a>", "</span>")    # <a> kennt nur Gtk.Label
    ok, _attrs, text, _acc = Pango.parse_markup(mk2, -1, "\0")
    return ok, text


ALLOWED = {"b", "i", "s", "tt", "span", "a"}


@pytest.mark.parametrize("v", VEC, ids=[repr(v["text"])[:40] for v in VEC])
def test_fmt_pango_valid_and_safe(v):
    """jeder Vektor ergibt gueltiges Pango-Markup, nur erlaubte Tags, keine Attribute aus dem Text"""
    from hlidskjalf.chatstd import message_markup
    mk = message_markup(v["text"], {}, {"members": []})
    ok, _t = _pango_ok(mk)
    assert ok
    assert {t for t in re.findall(r"</?([a-zA-Z_]+)", mk)} <= ALLOWED
    for href in re.findall(r'<a href="([^"]*)"', mk):
        assert re.match(r"https?://|saga-note:\d+$", href)
    for attrs in re.findall(r"<span([^>]*)>", mk):            # nur feste Attribute (nie aus dem Text)
        assert re.fullmatch(r'(?: (?:fgalpha|font_family|weight|foreground)="[^"]*")*', attrs), attrs


INJ = ['</span><span foreground="red" size="xx-large">rot</span>', '<a href="javascript:alert(1)">x</a>',
       "*<b>fett</b>*", "_</i><big>_", "`</tt><span>`", "> </span><u>", "- <a href='x'>l</a>",
       "```\n</tt></span>\n```", "&lt;b&gt; &amp; & <", "\x01\x07 steuer \x1b[31m", "<@~aaaaaaaaaaaaaaaa>\"><b>",
       "[x](javascript:alert(3))", "javascript:alert(1)", "https://ok.example/\"onmouseover=\"x", "\"' <>"]


@pytest.mark.parametrize("t", INJ)
def test_fmt_injection(t):
    """Markup-Injection: der Text erscheint 1:1 als Text, keine fremden Tags, kein javascript:-Link"""
    from hlidskjalf.chatstd import message_markup
    mk = message_markup(t, {}, {"members": []})
    ok, shown = _pango_ok(mk)
    assert ok
    assert {x for x in re.findall(r"</?([a-zA-Z_]+)", mk)} <= ALLOWED
    assert "javascript" not in "".join(re.findall(r'href="([^"]*)"', mk))
    plain = re.sub(r"[*_~`>•▎ \n�-]", "", shown)
    want = re.sub(r"[*_~`>•▎ \n\x00-\x1f-]", "", t)
    assert plain.replace("@Unknown", "") == want.replace("<@~aaaaaaaaaaaaaaaa>", "") or "<@" in t


def test_fmt_web_cases():
    """dieselben Faelle wie tests/browser_chat_alltag.js (Web)"""
    from hlidskjalf.chatstd import message_markup
    mk = message_markup('*fett* _kursiv_ ~weg~ `x<y`\n- eins\n- zwei\n> zitiert\n```\n<b>block</b>\n```', {}, {})
    assert "<b>fett</b>" in mk and "<i>kursiv</i>" in mk and "<s>weg</s>" in mk and "<tt>x&lt;y</tt>" in mk
    assert "• eins\n• zwei" in mk and "▎ zitiert" in mk and "&lt;b&gt;block&lt;/b&gt;" in mk
    mk = message_markup('<img src=x onerror="alert(1)"> *<script>alert(2)</script>* [x](javascript:alert(3)) a_b_c 2*3*4', {}, {})
    _ok, shown = _pango_ok(mk)
    assert '<img src=x onerror="alert(1)">' in shown and "a_b_c 2*3*4" in shown and mk.count("<b>") == 1 and "<a " not in mk
    # ohne chatfmt1: Zeichen bleiben sichtbar
    assert message_markup("*a*", {}, {}, fmt=False) == "*a*"


def test_links_and_notes_in_formatting():
    from hlidskjalf.chatstd import message_markup
    mk = message_markup("*siehe https://a.example/x_y* [[note:7]] [[note:8]] [[note:9]]", {7: {"title": "<T>", "icon": "📘"}, 8: None}, {})
    assert '<b>siehe <a href="https://a.example/x_y">https://a.example/x_y</a></b>' in mk
    assert '<a href="saga-note:7">📘 &lt;T&gt;</a>' in mk and "<i>Page (no access)</i>" in mk and "📄 …" in mk


# --- Erwaehnungen ----------------------------------------------------------------------------------------------------
def _mc():
    return {"kind": "free", "me_ref": HM, "my_handle": None,
            "members": [{"username": "me", "me": True, "ref": HM, "name": "Ich"}, {"username": "~a", "ref": H1, "name": "Anna"},
                        {"username": "x", "handle": H2, "name": "Bert <B>"}, {"username": "n", "name": "Ohne"}]}


def test_mention_tokens():
    from hlidskjalf.chatstd import (apply_people, mention_name, mention_refs, mentions_me, message_markup, people_for,
                                    plain_mentions)
    c = _mc()
    ppl = people_for(c, "")
    assert [p["ref"] for p in ppl] == [H1, H2]                    # ohne mich, ohne Kennung nicht
    v1 = {"kind": "free", "me_ref": "~" + "q" * 16, "members": [{"me": True, "ref": "me-login", "handle": "~" + "h" * 16 + "@s"},
                                                               {"name": "X", "ref": H1, "handle": "~" + "x" * 16 + "@s"}]}
    assert people_for(v1, "")[0]["ref"] == H1                     # Chat v1: ref, auch wenn der Server handle mitliefert
    v3 = dict(v1, v=3)
    assert people_for(v3, "")[0]["ref"] == "~" + "x" * 16 + "@s"  # Stufe 3: handle
    assert mentions_me(v1, "<@~" + "h" * 16 + "@s>") and mentions_me(v1, "<@~" + "q" * 16 + ">")
    assert [p["name"] for p in people_for(c, "ber")] == ["Bert <B>"]
    text, refs = apply_people("Hallo @Anna und @Bert <B>, @Anna!", {"@Anna": H1, "@Bert <B>": H2, "@Boese": "username"})
    assert text == f"Hallo <@{H1}> und <@{H2}>, <@{H1}>!" and refs == [H1, H2]
    assert mention_refs(text) == [H1, H2]
    assert mention_name(c, H1) == "Anna" and mention_name(c, H2.split("@")[0]) == "Bert <B>" and mention_name(c, "~" + "z" * 16) is None
    assert mention_name(c, HM, "Mein Name") == "Mein Name"
    assert mentions_me(c, f"x <@{HM}>") and not mentions_me(c, f"<@{H1}>")
    assert plain_mentions(f"<@{H1}> <@~{'z' * 16}>", c) == "@Anna @Unknown"
    mk = message_markup(f"*<@{H2}>* <@{HM}> <@~{'z' * 16}>", {}, c, "Ich")
    assert '<b><span weight="600" foreground="#3584e4">@Bert &lt;B&gt;</span></b>' in mk
    assert '<span weight="bold" foreground="#1c71d8">@Ich</span>' in mk and "@Unknown" in mk
    assert message_markup(f"<@{H1}>", {}, c, mention=False) == f"&lt;@{H1}&gt;"     # ohne chatmention1: Token bleibt Text
    many = " ".join(f"<@~{chr(97 + i % 26) * 15}{chr(97 + i // 26)}>" for i in range(60))
    assert len(mention_refs(many)) == 50


# --- Systemzeilen ----------------------------------------------------------------------------------------------------
def test_sys_perm_and_admin():
    from hlidskjalf.chat import sys_text
    me = "~me@h"
    by = {"name": "Anna", "handle": "~an@h", "host": "h"}
    assert sys_text({"type": "perm", "perm_send": "admins", "by": by}, me) == \
        "Anna changed the group permissions. Only admins can send messages now."
    assert sys_text({"type": "perm", "perm_info": "all", "perm_send": "all", "by": {"handle": me, "name": "Ich"}}, me) == \
        "You changed the group permissions. Everyone can send messages now. Everyone can change name, picture and description now."
    assert sys_text({"type": "perm", "perm_info": "admins", "by": None}, me) == \
        "An admin changed the group permissions. Only admins can change name, picture and description now."
    assert sys_text({"type": "perm", "perm_send": "bogus"}, me) == "An admin changed the group permissions."
    assert sys_text({"type": "admin", "target": by, "on": True, "by": {"handle": me}}, me) == "You made Anna an admin."
    assert sys_text({"type": "perm", "by": {"name": "Bo", "handle": "~b@o.example", "host": "o.example"}, "perm_send": "all"}, me) \
        .startswith("Bo (o.example) changed")


# --- Stummschalten, Liste ---------------------------------------------------------------------------------------------
def test_mute_label_and_order():
    from hlidskjalf.chatstd import list_split, mute_choices, mute_label, muted_now, notify_choices
    now = time.time()
    assert mute_label({"muted": False, "muted_until": 0}) == "Mute"
    assert mute_label({"muted": False, "muted_until": 0, "notify": "mentions"}) == "Mentions only"
    assert mute_label({"muted_until": 0, "notify": "none"}) == "Notifications off"
    assert mute_label({"muted": True, "muted_until": -1}) == "Muted"
    lab = mute_label({"muted": True, "muted_until": now + 3600})
    assert lab.startswith("Muted until ") and re.search(r"\d", lab)
    assert not muted_now({"muted": True, "muted_until": now - 5})                # abgelaufen: nicht mehr stumm
    assert muted_now({"muted": True})                                            # aelterer Server: nur muted
    ch = mute_choices(1000)
    assert [x[1] for x in ch] == [4600, 1000 + 8 * 3600, 1000 + 7 * 86400, -1]
    assert [x[0] for x in notify_choices()] == ["all", "mentions", "none"]
    lst = [{"id": 1}, {"id": 2, "pinned": True, "pinned_at": 5}, {"id": 3}, {"id": 4, "pinned": True, "pinned_at": 9}]
    assert [c["id"] for c in list_split(lst)] == [4, 2, 1, 3]


# --- Linkvorschau, Medien ---------------------------------------------------------------------------------------------
def test_unfurl_helpers():
    from hlidskjalf.chatstd import first_url, preview_body, preview_of
    assert first_url("schau: https://a.example/x?y=1). und http://b") == "https://a.example/x?y=1"
    assert first_url("javascript:alert(1) ftp://x") is None
    assert preview_body({}) is None and preview_body({"url": "javascript:x", "title": "t"}) is None
    p = preview_body({"url": "https://a.example", "title": "T" * 300, "desc": "d", "site": "S", "image": {"b64": "AA"}})
    assert p == {"url": "https://a.example", "title": "T" * 200, "desc": "d", "site": "S"}
    assert preview_of({"preview": {"url": "javascript:alert(1)", "title": "x"}}) is None
    q = preview_of({"preview": {"url": "https://a.example", "title": "<b>", "img": {"id": "i", "k": "k"}}})
    assert q["img"] == {"id": "i", "k": "k"} and q["title"] == "<b>"
    assert preview_of({"preview": {"url": "https://a", "img": {"id": "i"}}})["img"] is None


def test_unfurl_setting(srv):
    from hlidskjalf import config
    from hlidskjalf.chatstd import unfurl_enabled
    assert unfurl_enabled(ALLF) and not unfurl_enabled(set())
    config.settings.set("chat_unfurl", False)
    assert not unfurl_enabled(ALLF)
    config.settings.set("chat_unfurl", None)


def test_media_split():
    from hlidskjalf.chatstd import media_split
    msgs = [{"state": "ok", "text": "a https://x.example/1 b https://y.example", "files": [
        {"id": "1", "m": "image/png"}, {"id": "2", "m": "video/mp4"}, {"id": "3", "m": "application/pdf"},
        {"id": "4", "m": "audio/ogg", "voice": True}]}, {"state": "undecryptable", "files": [{"id": "5", "m": "image/png"}]}]
    med, fil, links = media_split(msgs)
    assert [f["id"] for f, _m in med] == ["1", "2"] and [f["id"] for f, _m in fil] == ["3"]
    assert [u for u, _m in links] == ["https://x.example/1", "https://y.example"]


# --- Sperren, Melden, Weiterleiten ------------------------------------------------------------------------------------
def test_block_report_forward():
    from hlidskjalf.chatstd import (block_target, dm_peer_target, fwd_body, fwd_candidates, fwd_files, grp_blocked, report_body,
                                    report_text, send_block_text)
    c = {"kind": "free", "members": [{"username": "me", "me": True}, {"username": "~a", "handle": "~a@h", "blocked": True},
                                     {"username": "b", "ref": "~b"}]}
    assert grp_blocked(c, {"sender": {"username": "~a"}}) and not grp_blocked(c, {"sender": {"username": "b"}})
    assert grp_blocked(c, {"sender": {}, "sender_handle": "~a@h"})
    assert not grp_blocked(dict(c, kind="dm"), {"sender": {"username": "~a"}})
    assert block_target(c, {"sender": {"username": "b"}}) == "~b" and block_target(c, {"sender_handle": "~q@h"}) == "~q@h"
    assert block_target(c, {"sender": {"username": "nobody"}}) is None
    dm = {"kind": "dm", "members": [{"me": True}, {"handle": "~p@h", "name": "Pia", "blocked": True}]}
    assert dm_peer_target(dm) == ("~p@h", "Pia", True) and dm_peer_target(c) is None
    assert send_block_text({"blocked": True}).startswith("⛔") and "Only admins" in send_block_text({})
    msgs = [{"id": "m1", "sender": {"name": "Anna"}, "text": "hi <@" + H1 + ">"}, {"id": "m2", "sender": {"name": "Bo"}, "text": "x"}]
    assert report_text(msgs, {"members": [{"ref": H1, "name": "Ich"}]}) == "Anna: hi @Ich\nBo: x"
    assert report_body(5, msgs, "spam", " t ") == {"chat": 5, "msgs": ["m1", "m2"], "reason": "spam", "text": "t"}
    assert report_body(5, msgs, "bogus")["reason"] == "other"
    assert len(report_body(5, [{"id": str(i)} for i in range(30)], "spam")["msgs"]) == 20
    cs = [{"id": 1, "name": "A"}, {"id": 2, "request": True}, {"id": 3, "readonly": True}, {"id": 4, "can_send": False},
          {"id": 5, "name": "Bert"}]
    assert [x["id"] for x in fwd_candidates(cs)] == [1, 5] and [x["id"] for x in fwd_candidates(cs, "ber")] == [5]
    ok, sk = fwd_files({"files": [{"id": "a"}, {"id": "v", "voice": True, "x": 2}, {"id": "x", "x": 2}, {"id": "n", "x": 3}, "kaputt",
                                  {"n": "ohne-id"}]})
    assert [f["id"] for f in ok] == ["a", "v", "x"] and sk == 3          # (2.5) v2 und Sprachnachrichten gehen mit
    from hlidskjalf.chatstd import fwd_entry
    v = {"id": "alt", "k": "K", "n": "voice.ogg", "m": "audio/ogg", "s": 9, "x": 2, "cs": 65536, "voice": True, "dur": 1.5, "wf": "AA",
         "_ad": 1, "th": {"id": "t"}}
    assert fwd_entry(v, "neu") == {"id": "neu", "k": "K", "n": "voice.ogg", "m": "audio/ogg", "s": 9, "x": 2, "cs": 65536,
                                   "voice": True, "dur": 1.5, "wf": "AA"}
    assert fwd_entry(v, "neu", {"id": "t2"})["th"] == {"id": "t2"}
    assert fwd_body({"text": "t", "notes": [1, "x"]}, [], 1) == {"v": 1, "type": "text", "text": "t\n[Attachment not forwarded]", "notes": [1]}
# --- Entwuerfe je Chat ------------------------------------------------------------------------------------------------
def test_chat_drafts(srv):
    from hlidskjalf import chatdrafts, config
    config.set_account("test")
    chatdrafts.save(5, "Hallo @Anna <@x>", {"@Anna": H1, "@Weg": H2, "@Boese": "anna-login"})
    assert chatdrafts.load(5) == {"t": "Hallo @Anna <@x>", "p": {"@Anna": H1}}     # nur benutzte, nur gueltige Kennungen
    assert chatdrafts.has(5) and not chatdrafts.has(6)
    config.set_account("other")
    assert chatdrafts.load(5) is None                          # anderes Konto sieht fremde Entwuerfe nie
    config.set_account("test")
    chatdrafts.save(5, "   ")
    assert chatdrafts.load(5) is None                          # leer = geloescht
    chatdrafts.save(7, "x" * 30000)
    assert len(chatdrafts.load(7)["t"]) == 20000
    chatdrafts.clear_account()
    assert chatdrafts.load(7) is None and not os.path.exists(chatdrafts.path())


# --- Fixtures aus echten Antworten des Testservers (tests/real25std.py mit HK_STD_FIXTURES, anonymisiert) ---------------
FIX = os.path.join(HERE, "fixtures25std")


def _fx(name):
    return json.load(open(os.path.join(FIX, name + ".json"), encoding="utf-8"))


def test_fixture_rights_admin_and_page():
    from hlidskjalf.chatstd import can_add_members, members_hint, page_member_action, rights
    c = _fx("chat_free_admin")
    r = rights(c, ALLF)
    assert r["info2"] and r["perms"] and r["pin"] and r["moderate"] and r["send"] and not r["hide"]
    assert c["perm_info"] == c["perm_send"] == "all" and c["pins"] == []
    p = _fx("chat_page_owner")
    assert can_add_members(p, ALLF) and members_hint(p, ALLF).startswith("Adding people shares")
    assert all(page_member_action(p, m, ALLF) is None for m in p["members"] if m.get("me"))
    assert p["can_hide"] is True and p["can_rename"] is False and p["can_admin"] is False


def test_fixture_list_order_and_fields():
    from hlidskjalf.chatstd import list_split, mute_label, unread_dot
    lst = _fx("chats_list")["chats"]
    assert list_split(lst)[0]["pinned"] is True
    for c in lst:
        assert isinstance(c["muted_until"], int) and c["notify"] in ("all", "mentions", "none")
        mute_label(c)
        unread_dot(c)


def test_fixture_tombs_and_blocks():
    from hlidskjalf.chat import ChatState
    t = _fx("messages_tombs")["messages"]
    assert t and all(m["kind"] == "tomb" and m["by_admin"] is True and m.get("key") in (None, "…") for m in t)
    st = ChatState()
    d = st._decode(1, t[0], {}, {}, {"kind": "free"})
    assert d["state"] == "tomb" and d["by_admin"] and not d["files"]
    bl = _fx("blocks")["blocks"]
    assert bl[0]["local"] is True and bl[0]["target"].startswith("~") and bl[0]["name"]


def test_fixture_unfurl_and_pins():
    from hlidskjalf.chatstd import preview_body
    u = _fx("unfurl")
    assert preview_body(u)["title"] == "Ein Artikel <zum> Test" and preview_body(u)["site"] == "Testblatt"
    p = _fx("chat_free_pins")
    assert len(p["pins"]) == 2
    d = _fx("chat_free_desc_avatar")
    assert d["desc_msg"]["kind"] == "desc" and d["avatar_msg"]["kind"] == "avatar"
