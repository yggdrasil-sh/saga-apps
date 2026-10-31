"""Chat-Standard (Saga 2.5) - reine Logik ohne Oberflaeche: Faehigkeiten (Feature-Gating), Rechte aus den can_*-Feldern,
Formatierung (Markdown-light wie static/chat-fmt.js im Server-Repo), Erwaehnungs-Token, Systemzeile 'perm', Stummschalt-
Anzeige, Linkvorschau, Medienuebersicht, Sperren/Melden, Weiterleiten. Referenz: docs/APP-API-CHAT.md 6, 6a, 8, 9.

Jede Funktion nur, wenn der Server das Feature in /api/version -> features meldet; sonst bleibt alles wie bisher.
Formatierung: parse() baut denselben Baum wie chat-fmt.js (Vektoren tests/vectors25/chat_fmt.json, erzeugt aus der
Web-Datei); pango() macht daraus Pango-Markup - jeder Text geht durch esc(), Marken werden nur als feste Tags erzeugt
(nie aus dem Text), Links nur http/https."""
import datetime as _dt
import re
import time

from .i18n import _

# --- Faehigkeiten --------------------------------------------------------------------------------------------------
PAGE_MEMBERS = "pagechatmembers1"
GROUPINFO2, MODDEL, MSGPIN, BLOCK2, REPORT = "groupinfo2", "moddel1", "msgpin1", "block2", "report1"
MUTE2, CHATPIN, MARKUNREAD, CHATHIDE = "mute2", "chatpin1", "markunread1", "chathide1"
MENTION, FMT, UNFURL, MEDIA = "chatmention1", "chatfmt1", "unfurl1", "chatmedia1"
ALL = (PAGE_MEMBERS, GROUPINFO2, MODDEL, MSGPIN, BLOCK2, REPORT, MUTE2, CHATPIN, MARKUNREAD, CHATHIDE, MENTION, FMT, UNFURL,
       MEDIA)

DESC_MAX = 500
GRP_AV = 256
PINS_MAX = 3
CHAT_PINS_MAX = 5
MENTIONS_MAX = 50
REPORT_MSGS_MAX = 20
REPORT_TEXT_MAX = 2000
FWD_MAX = 50
HIDE_MAX = 500
DRAFT_MAX = 20000


def has(feature, feats=None):
    """Server meldet das Feature. feats: Menge zum Pruefen (Tests); sonst store.features"""
    if feats is None:
        from .store import store
        feats = store.features
    return feature in (feats or ())


# --- Rechte (Felder des Chat-Objekts, docs/APP-API-CHAT.md 8/9) -------------------------------------------------------
def rights(c, feats=None):
    """-> dict der erlaubten Aktionen; ohne Feature immer False (aeltere Server: altes Verhalten)"""
    c = c if isinstance(c, dict) else {}
    kind = c.get("kind")
    ro = bool(c.get("readonly"))
    g2 = has(GROUPINFO2, feats)
    return {
        "info2": g2 and kind == "free" and c.get("can_info2") is True and not ro,
        "edit_info": g2 and kind == "free" and c.get("can_edit_info") is not False and not ro,
        "perms": g2 and kind == "free" and c.get("can_perms") is True and not ro,
        "show_perms": g2 and kind == "free" and (c.get("can_perms") is True or "admins" in (c.get("perm_info"), c.get("perm_send"))),
        "pin": has(MSGPIN, feats) and c.get("can_pin") is True,
        "moderate": has(MODDEL, feats) and c.get("can_moderate") is True,
        "send": not (g2 and c.get("can_send") is False) and not (has(BLOCK2, feats) and c.get("can_send") is False),
        "block": has(BLOCK2, feats),
        "report": has(REPORT, feats),
        "mute2": has(MUTE2, feats),
        "chatpin": has(CHATPIN, feats),
        "unread": has(MARKUNREAD, feats),
        "hide": has(CHATHIDE, feats) and c.get("can_hide") is True,
        "mention": has(MENTION, feats),
        "fmt": has(FMT, feats),
        "unfurl": has(UNFURL, feats),
        "media": has(MEDIA, feats),
    }


def send_block_text(c):
    """Hinweis statt des Eingabefelds bei can_send:false (gesperrte Person im Direkt-Chat bzw. nur Admins)"""
    if (c or {}).get("blocked"):
        return "⛔ " + _("You blocked this person. Unblock via the chat menu (⋯).")
    return "🔒 " + _("Only admins can send messages in this group.")


def can_add_members(c, feats=None):
    """"Mitglied hinzufuegen": freie Gruppe bei can_manage (wie bisher), Seiten-Chat bei can_manage (pagechatmembers1)"""
    c = c if isinstance(c, dict) else {}
    from .chat import gi_rights
    if c.get("kind") == "free":
        return gi_rights(c)["manage"]
    return c.get("kind") == "page" and has(PAGE_MEMBERS, feats) and c.get("can_manage") is True


def page_member_action(c, m, feats=None):
    """Seiten-Chat: "Aus Chat entfernen" -> None (kein Eintrag) | ("remove", None) | ("off", Erklaerung)"""
    c = c if isinstance(c, dict) else {}
    if c.get("kind") != "page" or not has(PAGE_MEMBERS, feats) or c.get("can_manage") is not True:
        return None
    from .chat import gi_mine
    if not isinstance(m, dict) or gi_mine(c, m):
        return None
    if m.get("removable") is True:
        return ("remove", None)
    via = m.get("via")
    why = (_("Access through a group – remove them there.") if via == "group" else
           _("Access through a parent page – revoke the share there.") if via == "parent" else
           _("They own the page.") if via == "owner" else
           _("Access not only through this page – check the share dialog."))
    return ("off", why)


def members_hint(c, feats=None):
    """Hinweis in der Gruppeninfo je Chat-Art (Text wie Web)"""
    c = c if isinstance(c, dict) else {}
    if c.get("kind") == "group":
        return _("Members come from the user group – manage them there.")
    if c.get("kind") == "page":
        if has(PAGE_MEMBERS, feats) and c.get("can_manage") is True:
            return _("Adding people shares this page with them. Removing revokes the share.")
        return _("Members are everyone who can see the page – the page owner decides.")
    return ""


PAGE_ROLES = ("view", "comment", "edit")


def page_role_labels():
    return {"view": _("View"), "comment": _("Comment"), "edit": _("Edit")}


# --- Systemzeile 'perm' (8b) -----------------------------------------------------------------------------------------
def perm_sys_text(k, my_handle):
    """{type:'perm', perm_info?, perm_send?, by} -> "{name} hat die Gruppenrechte geaendert. Nur Admins duerfen jetzt ..." """
    from .chat import sys_who
    k = k if isinstance(k, dict) else {}
    by = k.get("by") if isinstance(k.get("by"), dict) else None
    me = bool(by and by.get("handle") and by.get("handle") == my_handle)
    who = _("You") if me else (sys_who(by, my_handle) if by else _("An admin"))
    parts = []
    if k.get("perm_send") in ("all", "admins"):
        parts.append(_("Only admins can send messages now.") if k["perm_send"] == "admins" else _("Everyone can send messages now."))
    if k.get("perm_info") in ("all", "admins"):
        parts.append(_("Only admins can change name, picture and description now.") if k["perm_info"] == "admins"
                     else _("Everyone can change name, picture and description now."))
    return _("{name} changed the group permissions.").format(name=who) + (" " + " ".join(parts) if parts else "")


# --- Stummschalten (mute2), Chat anheften, ungelesen, ausblenden (9) ---------------------------------------------------
def muted_now(c, now=None):
    c = c if isinstance(c, dict) else {}
    mu = c.get("muted_until")
    if isinstance(mu, (int, float)) and not isinstance(mu, bool):
        now = time.time() if now is None else now
        return mu == -1 or mu > now
    return bool(c.get("muted"))


def _until_text(ts):
    from .util import time_text, weekday_day_text
    d = _dt.datetime.fromtimestamp(float(ts))
    today = _dt.date.today()
    if d.date() == today:
        return time_text(d)
    return weekday_day_text(d, year=d.year != today.year) + " " + time_text(d)


def mute_label(c, now=None):
    """Anzeige wie Web muteLabel: "Stummschalten" | "Nur Erwaehnungen" | "Mitteilungen aus" | "Stumm bis …" | "Stummgeschaltet" """
    c = c if isinstance(c, dict) else {}
    if not muted_now(c, now):
        n = c.get("notify")
        return _("Mentions only") if n == "mentions" else _("Notifications off") if n == "none" else _("Mute")
    mu = c.get("muted_until")
    if isinstance(mu, (int, float)) and not isinstance(mu, bool) and mu > 0:
        return _("Muted until {time}").format(time=_until_text(mu))
    return _("Muted")


def mute_choices(now=None):
    """-> [(Text, muted_until)] fuer das Untermenue (1 h, 8 h, 1 Woche, fuer immer)"""
    now = int(time.time() if now is None else now)
    return [(_("1 hour"), now + 3600), (_("8 hours"), now + 8 * 3600), (_("1 week"), now + 7 * 86400), (_("Forever"), -1)]


def notify_choices():
    return [("all", _("All messages")), ("mentions", _("Only mentions and replies")), ("none", _("None"))]


def list_split(chats):
    """Chatliste: angeheftete zuerst (nach pinned_at, wie der Server), Rest in der Reihenfolge des Servers"""
    lst = [c for c in chats or [] if isinstance(c, dict)]
    pin = sorted([c for c in lst if c.get("pinned")], key=lambda c: -(c.get("pinned_at") or 0))
    return pin + [c for c in lst if not c.get("pinned")]


def unread_dot(c):
    """markiert als ungelesen (ohne echte ungelesene Nachrichten): Punkt statt Zahl"""
    c = c if isinstance(c, dict) else {}
    return bool(c.get("unread_mark"))


# --- Erwaehnungen (chatmention1, 9.3) --------------------------------------------------------------------------------
REF_RX = r"~[a-z2-7]{16}(?:@[a-z0-9.:\-]{1,260})?"
MENTION_RX = re.compile(r"<@(" + REF_RX + r")>")


def mem_ref(m):
    """Kennung eines Mitglieds im Token: handle (Stufe 3/ueber Servergrenzen) bzw. ref (v1) - nie der Anmeldename"""
    m = m if isinstance(m, dict) else {}
    r = m.get("handle") or m.get("ref") or ""
    return r if isinstance(r, str) and re.fullmatch(REF_RX, r) else ""


def people_for(c, q, limit=6):
    """@-Auswahl: Mitglieder (ohne mich) mit Kennung, Anzeigename enthaelt q -> [{name, ref, avatar}]"""
    ql = str(q or "").lower()
    out = []
    for m in (c or {}).get("members") or []:
        if not isinstance(m, dict) or m.get("me"):
            continue
        r = mem_ref(m)
        if r and r != (c or {}).get("me_ref") and ql in str(m.get("name") or "").lower():
            out.append({"name": str(m.get("name") or "?"), "ref": r, "avatar": m.get("avatar")})
    return out[:limit]


def apply_people(text, people):
    """Eingefuegte "@Name" (people: Text -> Kennung) -> Token <@kennung>; laengste zuerst -> (text, [kennungen])"""
    used = set()
    for label in sorted(people or {}, key=len, reverse=True):
        r = people[label]
        if not label or label not in text or not re.fullmatch(REF_RX, str(r)):
            continue
        text = text.replace(label, "<@" + r + ">")
        used.add(r)
    return text, [r for r in mention_refs(text) if r in used]


def mention_refs(text):
    """alle Kennungen aus den Token eines Texts (Reihenfolge, ohne Doppelte, hoechstens 50)"""
    out = []
    for r in MENTION_RX.findall(text or ""):
        if r not in out:
            out.append(r)
    return out[:MENTIONS_MAX]


def mention_name(c, r, my_name=None):
    """Kennung -> Anzeigename (ich: eigener Name), unbekannt -> None"""
    c = c if isinstance(c, dict) else {}
    if r and r in (c.get("me_ref"), c.get("my_handle")):
        return my_name or _("You")
    for m in c.get("members") or []:
        if not isinstance(m, dict):
            continue
        h = m.get("handle") or ""
        if r in (h, m.get("ref")) or (h and h.split("@")[0] == r):
            return str(m.get("name") or "?")
    return None


def mention_is_me(c, r):
    c = c if isinstance(c, dict) else {}
    return bool(r) and r in (c.get("me_ref"), c.get("my_handle"))


def mentions_me(c, text):
    c = c if isinstance(c, dict) else {}
    t = text or ""
    return any(x and "<@" + x + ">" in t for x in (c.get("me_ref"), c.get("my_handle")))


def plain_mentions(text, c, my_name=None):
    """Token -> "@Name" (Vorschauen, Kopieren, Melden)"""
    return MENTION_RX.sub(lambda m: "@" + (mention_name(c, m.group(1), my_name) or _("Unknown")), text or "")


# --- Formatierung (chatfmt1, 9.4) - Port von static/chat-fmt.js ---------------------------------------------------------
# JS-"\s" (ECMAScript WhiteSpace + LineTerminator) - Pythons \s weicht ab (z. B. \x1c-\x1f, ﻿)
_WS = "\t\n\x0b\x0c\r    -     　﻿"
INL = re.compile(r"(\A|[" + _WS + r"(\[{\"'])([*_~])([^" + _WS + r"*_~\n](?:[^\n]*?[^" + _WS + r"\n])?)\2(?=\Z|[" + _WS +
                 r")\]}\"'.,;:!?])")
CODE = re.compile(r"`([^`\n]+)`")
FENCE = re.compile(r"```(?:[a-zA-Z0-9_+-]{0,20}\n)?([\s\S]*?)```")
MARK = {"*": "strong", "_": "em", "~": "s"}
CLS = {"strong": "ct-f-strong", "em": "ct-f-em", "s": "ct-f-s", "code": "ct-f-code", "pre": "ct-f-pre", "ul": "ct-f-list",
       "blockquote": "ct-f-quote"}


class Node:
    """Knoten wie im DOM von chat-fmt.js: tag None = Textknoten (text), sonst Element mit Kindern"""
    __slots__ = ("tag", "cls", "text", "kids")

    def __init__(self, tag=None, cls="", text=""):
        self.tag, self.cls, self.text, self.kids = tag, cls, text, []

    def add(self, n):
        self.kids.append(n)
        return n


def _leaf(box, s):
    box.add(Node(text=s))


def _el(tag, plain=False):
    return Node(tag, "" if plain else CLS.get(tag, ""))


def _inline(box, s, depth):
    if not s:
        return
    mc = CODE.search(s)
    if mc:
        _inline(box, s[:mc.start()], depth)
        c = box.add(_el("code"))
        c.add(Node(text=mc.group(1)))
        _inline(box, s[mc.end():], depth)
        return
    m = INL.search(s) if depth < 4 else None
    if not m:
        _leaf(box, s)
        return
    start = m.start() + len(m.group(1))
    if start > 0:
        _leaf(box, s[:start])
    w = Node(MARK[m.group(2)], "ct-f-" + MARK[m.group(2)])
    _inline(w, m.group(3), depth + 1)
    box.add(w)
    _inline(box, s[m.end():], depth)


def _lines(box, text):
    ls = text.split("\n")
    i, first = 0, [True]

    def nl():
        if not first[0]:
            box.add(Node(text="\n"))
        first[0] = False
    while i < len(ls):
        ln = ls[i]
        if ln.startswith(">"):
            q = _el("blockquote")
            qs = []
            while i < len(ls) and ls[i].startswith(">"):
                qs.append(re.sub(r"\A> ?", "", ls[i]))
                i += 1
            _lines(q, "\n".join(qs))
            if not first[0] and box.kids and box.kids[-1].tag is None:
                box.kids[-1].text = re.sub(r"\n\Z", "", box.kids[-1].text)
            box.add(q)
            first[0] = True
            continue
        if re.match(r"[-•] ", ln):
            ul = _el("ul")
            while i < len(ls) and re.match(r"[-•] ", ls[i]):
                li = ul.add(_el("li", plain=True))
                _inline(li, ls[i][2:], 0)
                i += 1
            box.add(ul)
            first[0] = True
            continue
        nl()
        _inline(box, ln, 0)
        i += 1


def parse(text):
    """Text -> Wurzelknoten (wie render(box, text) in chat-fmt.js)"""
    s = str(text or "")
    box = Node("div")
    last = 0
    for m in FENCE.finditer(s):
        before = re.sub(r"\n\Z", "", s[last:m.start()])
        if before:
            _lines(box, before)
        pre = box.add(_el("pre"))
        code = pre.add(_el("code", plain=True))
        code.add(Node(text=re.sub(r"\n\Z", "", m.group(1))))
        last = m.end()
        if s[last:last + 1] == "\n":
            last += 1
    if last < len(s):
        _lines(box, s[last:])
    return box


def strip(text):
    """Marken entfernen (Vorschauen in Liste/Zitat) - wie SagaChatFmt.strip"""
    s = str(text or "")
    s = re.sub(r"```(?:[a-zA-Z0-9_+-]{0,20}\n)?", "", s)
    s = CODE.sub(lambda m: m.group(1), s)
    s = INL.sub(lambda m: m.group(1) + m.group(3), s)
    return re.sub(r"(?:\A|(?<=[\n\r  ]))(?:> ?|[-•] )", "", s)


def _html_esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def html(node):
    """Baum -> HTML wie innerHTML im Browser (nur fuer die Vektor-Tests: gleiche Struktur wie chat-fmt.js)"""
    def ser(n):
        if n.tag is None:
            return _html_esc(n.text)
        return "<" + n.tag + (' class="' + n.cls + '"' if n.cls else "") + ">" + "".join(ser(k) for k in n.kids) + "</" + n.tag + ">"
    return "".join(ser(k) for k in node.kids)


# Zeichen, die Pango/GMarkup auch als Referenz nicht annimmt (XML 1.0): durch U+FFFD ersetzen
_BAD = re.compile("[\x00-\x08\x0b\x0c\x0e-\x1f￾￿]")


def esc(s):
    """Text -> sicher fuer Pango-Markup (auch Anfuehrungszeichen, ungueltige Steuerzeichen)"""
    return _BAD.sub("�", str(s or "")).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") \
        .replace('"', "&quot;").replace("'", "&#39;")


PANGO = {"strong": ("<b>", "</b>"), "em": ("<i>", "</i>"), "s": ("<s>", "</s>"), "code": ("<tt>", "</tt>")}
QUOTE_BAR = "▎ "


def pango(node, leaf=None):
    """Baum -> Pango-Markup. leaf(text) -> Markup fuer Textstuecke (Links, Seiten, Erwaehnungen); Standard: nur esc"""
    leaf = leaf or esc
    out = []
    state = {"block": False}

    def put(s):
        if not s:
            return
        if state["block"] and not s.startswith("\n"):
            out.append("\n")
        state["block"] = False
        out.append(s)

    def block(s):
        if out and not "".join(out[-1:]).endswith("\n"):
            out.append("\n")
        state["block"] = False
        out.append(s)
        state["block"] = True

    def inl(n):
        if n.tag is None:
            return leaf(n.text)
        if n.tag in PANGO:
            a, b = PANGO[n.tag]
            body = esc("".join(k.text for k in n.kids if k.tag is None)) if n.tag == "code" else "".join(inl(k) for k in n.kids)
            return a + body + b
        return "".join(inl(k) for k in n.kids)

    for k in node.kids:
        if k.tag is None or k.tag in PANGO:
            put(inl(k))
        elif k.tag == "pre":
            code = "".join(x.text for c in k.kids for x in c.kids if x.tag is None)
            block('<span font_family="monospace">' + esc(code) + "</span>")
        elif k.tag == "ul":
            block("\n".join("• " + "".join(inl(x) for x in li.kids) for li in k.kids))
        elif k.tag == "blockquote":
            inner = pango(k, leaf)
            block('<span fgalpha="70%">' + QUOTE_BAR + inner.replace("\n", "\n" + QUOTE_BAR) + "</span>")
    return "".join(out)


# Textstuecke: Seitenverweise, Adressen (nur http/https), Erwaehnungen - wie TOKEN in static/chat.js
TOKEN = re.compile(r"\[\[note:(\d+)\]\]|(https?://[^\s<>\"'`]+[^\s<>\"'`.,;:!?)\]])|<@(" + REF_RX + r")>")


def leaf_markup(text, notes, c=None, my_name=None, mention=True):
    """Textstueck -> Markup: alles escaped; [[note:ID]] als Link (ohne Zugriff kursiv), Adressen als Link, Token als
    hervorgehobener Name (eigener fett + Akzent)"""
    out, pos = [], 0
    for m in TOKEN.finditer(text or ""):
        out.append(esc(text[pos:m.start()]))
        if m.group(1):
            nid = int(m.group(1))
            n = (notes or {}).get(nid)
            if n:
                t = (n.get("icon") + " " if n.get("icon") and not n["icon"].startswith("/") else "📄 ") + (n.get("title") or _("Untitled"))
                out.append(f'<a href="saga-note:{nid}">{esc(t)}</a>')
            elif nid in (notes or {}):
                out.append("<i>" + esc(_("Page (no access)")) + "</i>")
            else:
                out.append("📄 …")
        elif m.group(2):
            u = m.group(2)
            out.append(f'<a href="{esc(u)}">{esc(u)}</a>')
        elif mention:
            r = m.group(3)
            nm = "@" + (mention_name(c, r, my_name) or _("Unknown"))
            if mention_is_me(c, r):
                out.append('<span weight="bold" foreground="#1c71d8">' + esc(nm) + "</span>")
            else:
                out.append('<span weight="600" foreground="#3584e4">' + esc(nm) + "</span>")
        else:
            out.append(esc(m.group(0)))
        pos = m.end()
    out.append(esc((text or "")[pos:]))
    return "".join(out)


def message_markup(text, notes, c=None, my_name=None, fmt=True, mention=True):
    """Nachrichtentext -> Pango-Markup (Formatierung nur mit chatfmt1, Erwaehnungen nur mit chatmention1)"""
    def leaf(s):
        return leaf_markup(s, notes, c, my_name, mention)
    if not fmt:
        return leaf(text or "")
    return pango(parse(text), leaf)


# --- Linkvorschau (unfurl1, 9.5) -------------------------------------------------------------------------------------
URL1 = re.compile(r"(https?://[^\s<>\"'`]+[^\s<>\"'`.,;:!?)\]])")


def first_url(text):
    m = URL1.search(text or "")
    return m.group(1) if m else None


def preview_body(d):
    """Antwort von GET /api/chat/unfurl -> body.preview (ohne Bild) oder None (nichts Brauchbares)"""
    if not isinstance(d, dict) or not (d.get("title") or d.get("desc")):
        return None
    u = str(d.get("url") or "")
    if not re.match(r"https?://", u):
        return None
    return {"url": u[:2000], "title": str(d.get("title") or "")[:200], "desc": str(d.get("desc") or "")[:300],
            "site": str(d.get("site") or "")[:80]}


def preview_of(body):
    """body.preview einer empfangenen Nachricht pruefen -> dict oder None (nur http/https, Laengen begrenzt)"""
    p = (body or {}).get("preview") if isinstance(body, dict) else None
    if not isinstance(p, dict):
        return None
    u = str(p.get("url") or "")
    if not re.match(r"https?://", u):
        return None
    img = p.get("img") if isinstance(p.get("img"), dict) and p["img"].get("id") and p["img"].get("k") else None
    return {"url": u[:2000], "title": str(p.get("title") or "")[:200], "desc": str(p.get("desc") or "")[:300],
            "site": str(p.get("site") or "")[:80], "img": img}


def unfurl_enabled(feats=None):
    """Server-Schalter (unfurl1) und Nutzereinstellung "Linkvorschau" (Standard an)"""
    from . import config
    return has(UNFURL, feats) and config.settings.get("chat_unfurl") is not False


# --- Medienuebersicht (chatmedia1, 9.6) ------------------------------------------------------------------------------
def media_split(msgs):
    """entschluesselte Nachrichten -> (medien [(f, m)], dateien [(f, m)], links [(url, m)]) - Sprachnachrichten nicht"""
    from . import chatmedia, chatvideo
    med, fil, links = [], [], []
    for m in msgs or []:
        if not isinstance(m, dict) or m.get("state") not in ("ok", "unverified"):
            continue
        for f in m.get("files") or []:
            if not isinstance(f, dict) or f.get("voice"):
                continue
            (med if chatmedia.is_image(f) or chatvideo.is_video(f) else fil).append((f, m))
        for u in URL1.findall(m.get("text") or ""):
            links.append((u, m))
    return med, fil, links


# --- Sperren und Melden (block2, report1) ----------------------------------------------------------------------------
def grp_blocked(c, m):
    """Nachricht einer von mir gesperrten Person in einer Gruppe (einklappen)"""
    c = c if isinstance(c, dict) else {}
    if c.get("kind") == "dm" or not isinstance(m, dict) or m.get("mine"):
        return False
    u = (m.get("sender") or {}).get("username")
    h = m.get("sender_handle")
    return any(isinstance(x, dict) and x.get("blocked") and ((u and u in (x.get("username"), x.get("handle"))) or
                                                           (h and h == x.get("handle")))
               for x in c.get("members") or [])


def block_target(c, m):
    """Handle der Person einer Nachricht (nie der Anmeldename) -> '~x@host' | '~x' | None"""
    if isinstance(m, dict) and m.get("sender_handle"):
        return m["sender_handle"]
    u = ((m or {}).get("sender") or {}).get("username")
    x = next((y for y in (c or {}).get("members") or [] if isinstance(y, dict) and y.get("username") == u), None)
    return (x.get("handle") or x.get("ref")) if x else None


def dm_peer_target(c):
    """Direkt-Chat: Gegenueber als Sperr-Ziel -> (handle, name, gesperrt?) oder None"""
    c = c if isinstance(c, dict) else {}
    if c.get("kind") != "dm":
        return None
    p = next((m for m in c.get("members") or [] if isinstance(m, dict) and not m.get("me")), None) or {}
    tg = p.get("handle") or (c.get("peer") or {}).get("handle") or p.get("ref")
    return (tg, str(p.get("name") or "?"), bool(p.get("blocked"))) if tg else None


REPORT_REASONS = ("spam", "abuse", "illegal", "other")


def report_reason_labels():
    return {"spam": _("Spam"), "abuse": _("Harassment or insult"), "illegal": _("Illegal content"), "other": _("Other")}


def report_text(msgs, c=None, my_name=None):
    """Vorbelegung des Textfelds: "Name: Text" je gemeldeter Nachricht (hoechstens 2000 Zeichen)"""
    lines = []
    for m in msgs or []:
        nm = ((m or {}).get("sender") or {}).get("name")
        lines.append((nm + ": " if nm else "") + plain_mentions((m or {}).get("text") or "", c, my_name))
    return "\n".join(lines)[:REPORT_TEXT_MAX]


def report_body(cid, msgs, reason, text=None):
    if reason not in REPORT_REASONS:
        reason = "other"
    b = {"chat": int(cid), "msgs": [m["id"] for m in msgs or [] if isinstance(m, dict) and m.get("id")][:REPORT_MSGS_MAX],
         "reason": reason}
    t = (text or "").strip()[:REPORT_TEXT_MAX]
    b["text"] = t
    return b


# --- Weiterleiten (Mehrfachauswahl, wie Web selForward) -----------------------------------------------------------------
def fwd_candidates(chats, q="", title=None):
    """Ziele: keine Anfragen, nicht schreibgeschuetzt, nicht can_send:false"""
    ql = (q or "").strip().lower()
    out = []
    for c in chats or []:
        if not isinstance(c, dict) or c.get("request") or c.get("readonly") or c.get("can_send") is False:
            continue
        name = title(c) if title else str(c.get("name") or "")
        if ql and ql not in name.lower():
            continue
        out.append(c)
    return out[:60]


def fwd_files(m):
    """Anhaenge einer Nachricht -> (weiterleitbar [f], uebersprungen n): Sprachnachrichten, saga-file/v2 nicht"""
    ok, skipped = [], 0
    for f in ((m or {}).get("files") or [])[:10]:
        if not isinstance(f, dict) or f.get("voice") or (f.get("x") and f.get("x") != 1):
            skipped += 1
            continue
        ok.append(f)
    return ok, skipped


def fwd_body(m, files, skipped):
    """Inhalt der weitergeleiteten Nachricht (neu verschluesselt im Ziel) - Erwaehnungs-Token bleiben Text"""
    text = str((m or {}).get("text") or "")
    if skipped:
        text = (text + "\n" if text else "") + _("[Attachment not forwarded]")
    body = {"v": 1, "type": "text", "text": text}
    if files:
        body["files"] = files
    notes = [n for n in (m or {}).get("notes") or [] if isinstance(n, int)][:20]
    if notes:
        body["notes"] = notes
    return body


def chunks(ids, n):
    return [ids[i:i + n] for i in range(0, len(ids), n)]
