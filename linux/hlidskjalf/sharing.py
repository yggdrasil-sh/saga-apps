"""Teilen mit Personen und Gruppen (Server ab 1.3): Rollen, Kopfzeile fremder Seiten, Teilen-Dialog, Text-Dialog
(Entwurf nach einem Konflikt, Zugriff verloren). Wortlaut wie im Web (static/pages.js), Glossar "Teilen und Kommentare".

Rollen: owner (Eigentuemer) > edit (Bearbeiten) > comment (Kommentieren) > view (Lesen). Eine fremde Seite liefert
role, owner{name, username, me}, created_by/updated_by; ohne Feld 'role' (aeltere Server) ist es die eigene Seite.
Schnittstelle: GET/PUT/DELETE api/notes/{id}/shares, GET api/share/targets?q= (Server ab 2.3 ab 1 Zeichen, sonst ab 3).
Die Logik (Zustand -> Anzeige, Rolle -> erlaubte Aktionen) steht ohne GTK oben und ist so per pytest pruefbar."""
from gi.repository import Adw, GLib, Gtk, Pango

from .api import client
from .i18n import _, ngettext, pgettext
from .store import store
from .util import bg, plain, rel_time

RANK = {None: 0, "view": 1, "comment": 2, "edit": 3, "owner": 4}
ROLES = ("view", "comment", "edit")
SEARCH_MIN = 3                  # Personensuche ab 3 Zeichen (Server bis 2.2, wie im Web)


def search_min():
    """Personensuche ab wie vielen Zeichen: Server ab 2.3 ab 1 (bei 1-2 Zeichen liefert er nur genaue Treffer, z. B. "hd"),
    aeltere Server ab 3. Version noch unbekannt: wie bisher ab 3."""
    from . import version_tuple
    from .store import store
    v = store.server_version
    return 1 if v and version_tuple(v) >= (2, 3, 0) else SEARCH_MIN
SEARCH_DELAY_MS = 200
GAP = 8                         # Abstand Feld -> Hinweis/Chips im Teilen-Dialog
# Aktionen, die nur der Eigentuemer hat (Seitenmenue, Seitenleiste, Befehlspalette)
OWNER_ONLY = frozenset({"move", "trash", "archive", "publish", "share", "versions", "template", "favorite", "lock",
                        "duplicate", "home"})
# Seiten anderer Saga-Instanzen (Foederation, negative IDs): nie ueber Servergrenzen - Rest je nach remote.caps (2.3)
REMOTE_NEVER = frozenset({"trash", "archive", "publish", "share", "template", "favorite", "lock", "duplicate", "home", "ai"})
# Aktion -> Faehigkeit des Servers der Seite (remote.caps, docs/APP-API-FED.md Abschnitt 8). Fehlt sie (Server 2.1/2.2),
# bleibt es bei Lesen, Kommentieren und Titel/Inhalt bearbeiten.
CAP_OF = {"move": "subpages", "subpage": "subpages", "upload": "upload", "versions": "versions", "export": "export",
          "wide": "db", "icon": "db", "cover": "db", "tags": "db", "props": "db", "db": "db", "presence": "presence",
          "chat": "chatgroups"}
# frueherer Name (alles aus CAP_OF und REMOTE_NEVER): ohne Faehigkeiten nie
REMOTE_OFF = REMOTE_NEVER | frozenset(CAP_OF)
PAGE_CAPS = ("db", "subpages", "upload", "versions", "export", "presence", "live", "chatgroups")


def is_remote(page):
    """Seite einer anderen Saga-Instanz? (negative ID bzw. Feld remote)"""
    if not isinstance(page, dict):
        return False
    try:
        if int(page.get("id") or 0) < 0:
            return True
    except (TypeError, ValueError):
        pass
    return isinstance(page.get("remote"), dict)


def remote_caps(page):
    """Was der Server einer fremden Seite kann (remote.caps, 2.3) als Menge; eigene Seiten: None"""
    if not is_remote(page):
        return None
    r = page.get("remote")
    caps = r.get("caps") if isinstance(r, dict) else None
    return frozenset(x for x in caps if isinstance(x, str)) if isinstance(caps, list) else frozenset()


def remote_can(page, cap):
    """Kann der Server der Seite das? Eigene Seiten (und Seiten dieses Servers): immer"""
    caps = remote_caps(page)
    return caps is None or cap in caps


def editor_extras(page):
    """Versionen und Verschieben (im geteilten Bereich) auch fuer 'Bearbeiten': Server ab 2.3 (lokal) bzw. Seiten anderer
    Server, deren Server es kann"""
    if is_remote(page):
        return True                                       # entscheidet CAP_OF (versions, subpages)
    from . import version_tuple
    from .store import store
    v = store.server_version
    return bool(v) and version_tuple(v) >= (2, 3, 0)


def remote_host(page):
    r = (page or {}).get("remote") if isinstance(page, dict) else None
    return (r.get("host") or r.get("name") or "") if isinstance(r, dict) else ""


# =====================================================================================================
# Logik ohne GTK
# =====================================================================================================
def role_of(page):
    """'owner' | 'edit' | 'comment' | 'view' - ohne Feld (aeltere Server, eigene Seiten): 'owner'"""
    r = (page or {}).get("role") if isinstance(page, dict) else None
    return r if r in ("owner", "edit", "comment", "view") else "owner"


def is_owner(page):
    return role_of(page) == "owner"


def foreign(page):
    return not is_owner(page)


def can_edit(page):
    return RANK[role_of(page)] >= RANK["edit"]


def can_comment(page):
    return RANK[role_of(page)] >= RANK["comment"]


def read_only(page):
    """Lesen/Kommentieren: der Editor ist schreibgeschuetzt (kein Platzhalter, keine Griffe)"""
    return not can_edit(page)


def allowed(page, action):
    """Darf die Rolle diese Aktion? Nur-Eigentuemer: Loeschen, Verschieben, Archiv, Veroeffentlichen, Teilen, Versionen,
    Vorlage, Favorit, Sperren, Duplizieren, Startseite. 'wide' (volle Breite) und 'subpage' ab Bearbeiten, Rest alle.
    Server ab 2.3: Versionen und Verschieben (im geteilten Bereich) auch mit Bearbeiten.
    Seiten anderer Saga-Instanzen: nie REMOTE_NEVER, sonst nur, was ihr Server kann (CAP_OF/remote.caps)."""
    rem = is_remote(page)
    if rem and (action in REMOTE_NEVER or (action in CAP_OF and not remote_can(page, CAP_OF[action]))):
        return False
    r = role_of(page)
    if r == "owner":
        return True
    if action in ("versions", "move") and editor_extras(page):
        return RANK[r] >= RANK["edit"]
    if action in OWNER_ONLY:
        return False
    if rem and action in ("icon", "cover", "tags", "props"):
        return RANK[r] >= RANK["edit"]
    if action in ("wide", "subpage", "upload"):
        return RANK[r] >= RANK["edit"]
    return True


def shared_move_targets(page, shared):
    """Verschieben fuer Bearbeiter (Server ab 2.3, auch Seiten anderer Server): Ziele im selben geteilten Baum - gleicher
    Eigentuemer, gleicher Server, Rolle Bearbeiten, keine Datenbanken, nicht unter sich selbst, nicht die jetzige
    Elternseite. Ob die Freigaben gleich sind, prueft der Server (sonst 403 mit Hinweis)."""
    if not isinstance(page, dict) or page.get("id") is None:
        return []
    pid = page["id"]
    owner = (page.get("owner") or {}).get("username") if isinstance(page.get("owner"), dict) else None
    host, rem = remote_host(page) or None, is_remote(page)
    kids = {}
    for p in shared:
        kids.setdefault(p.get("parent_id"), []).append(p["id"])
    blocked, stack = {pid}, [pid]
    while stack:
        for k in kids.get(stack.pop(), ()):
            if k not in blocked:
                blocked.add(k)
                stack.append(k)
    dbs = {p["id"] for p in shared if p.get("kind") == "database"}
    out = []
    for p in shared:
        if p["id"] in blocked or p["id"] == page.get("parent_id") or p["id"] in dbs or p.get("parent_id") in dbs or p.get("role") != "edit":
            continue                                       # auch keine Eintraege von Datenbanken
        if (p.get("owner") or {}).get("username") != owner or is_remote(p) != rem or (remote_host(p) or None) != host:
            continue
        if rem and not remote_can(p, "subpages"):
            continue
        out.append(p)
    return out


def role_name(r):
    """Rollen-Etikett (im Englischen "Can edit" statt der Aktion "Edit")"""
    return {"owner": _("Owner"), "edit": _("Can edit"), "comment": _("Can comment"), "view": _("Can view")}.get(r, "")


def role_you(r):
    return {"edit": _("You can edit"), "comment": _("You can view and comment"), "view": _("You can view")}.get(r, "")


def role_help(r):
    return {"view": _("Can view: see and export the page, subpages and entries."),
            "comment": _("Can comment: view and write comments."),
            "edit": _("Can edit: change content and create subpages. Deleting, moving and sharing stay with you.")}.get(r, "")


def role_hint(page):
    """Erklaerung fuer schreibgeschuetzte fremde Seiten (Tooltip am Rollen-Etikett)"""
    r = role_of(page)
    if r == "view":
        return _("👁 You can only view this page.")
    if r == "comment":
        return _("💬 You can view and comment on this page, but not edit it.")
    if r == "edit" and (page or {}).get("locked"):
        return _("🔒 The owner locked this page.")
    return ""


def owner_name(page):
    o = (page or {}).get("owner")
    return (o.get("name") or o.get("username") or "") if isinstance(o, dict) else ""


def shared_by(page):
    """Kopfzeile einer fremden Seite: ("Shared by {name}"-Vorlage, Name, "You can …") oder None (eigene Seite)"""
    if not foreign(page):
        return None
    return _("Shared by {name}"), owner_name(page), role_you(role_of(page))


def shared_by_tooltip(meta):
    """Seitenleiste, oberste geteilte Seite: "Shared by {name} · {role}" """
    o = (meta or {}).get("owner")
    if not isinstance(o, dict):
        return ""
    return _("Shared by {name} · {role}").format(name=o.get("name") or o.get("username") or "", role=role_you(role_of(meta)))


def meta_lines(page, now=None):
    """"Angelegt von …" / "Zuletzt geaendert von … · vor …" - nur, wenn es nicht man selbst war"""
    p, out = page or {}, []
    cb, ub = p.get("created_by"), p.get("updated_by")
    if isinstance(cb, dict) and not cb.get("me") and cb.get("name"):
        out.append(_("Created by {name}").format(name=cb["name"]))
    if isinstance(ub, dict) and not ub.get("me") and ub.get("name") and p.get("updated"):
        out.append(_("Last changed by {name} · {when}").format(name=ub["name"], when=rel_time(p["updated"], now)))
    return out


def via_label(v):
    """Weg, ueber den jemand zusaetzlich Zugriff hat: Gruppenname oder „Seitentitel“"""
    if (v or {}).get("type") == "group":
        return v.get("name") or ""
    return _("“{title}”").format(title=(v or {}).get("title") or _("Untitled"))


def group_sub(members):
    if members is None:
        return _("Group")
    return ngettext("Group · {n} person", "Group · {n} people", int(members)).format(n=int(members))


def is_member(hit, members):
    """2.4 (W4): Treffer der Personensuche (Handle '~…', aeltere Server: Benutzername) schon Mitglied des Chats?
    Chat-Mitglieder tragen username (Krypto, nie zeigen) und ref (derselbe Handle wie in der Personensuche)."""
    return bool(hit) and any(isinstance(m, dict) and hit in (m.get("username"), m.get("ref")) for m in members or [])


def share_rows(st):
    """Antwort von .../shares -> Zeilen der Liste "Zugriff":
    [{type, target, name, sub, role, via (Text oder None), remove_body, a11y_role, a11y_remove}]"""
    out = []
    for x in (st or {}).get("shares") or []:
        typ, name = x.get("type"), x.get("name") or x.get("target") or ""
        sub = group_sub(x.get("members")) if typ == "group" else remote_sub(typ) if typ in ("remote", "remote_group") else \
            _("Person")                                   # 2.4 (W4): nie den Anmeldenamen
        vias = (x.get("via") or []) if typ == "user" else []
        top = vias[0] if vias else None
        via = None
        if top and RANK.get(top.get("role"), 0) > RANK.get(x.get("role"), 0):      # ueber Gruppe/Elternseite mehr Rechte
            via = _("(via {via}: {role})").format(via=via_label(top), role=role_name(top.get("role")))
        if top:
            body = _("{name} keeps the role {role} via {via}.").format(name=name, role=role_name(top.get("role")), via=via_label(top))
        elif typ in ("group", "remote_group"):
            body = _("The group {name} immediately loses access to this page and its subpages.").format(name=name)
        else:
            body = _("{name} immediately loses access to this page and its subpages.").format(name=name)
        out.append({"type": typ, "target": x.get("target") or "", "name": name, "sub": sub, "role": x.get("role") or "view",
                    "avatar": x.get("avatar"), "via": via, "remove_body": body, "a11y_role": _("Role of {name}").format(name=name),
                    "a11y_remove": _("Remove access for {name}").format(name=name)})
    return out


def inherited_rows(st):
    """Freigaben von Elternseiten (nur lesend): [{type, name, sub, role, from, note_id}]"""
    out = []
    for x in (st or {}).get("inherited") or []:
        typ = x.get("type")
        out.append({"type": typ, "name": x.get("name") or x.get("target") or "",
                    "sub": group_sub(None) if typ == "group" else _("Person") if typ == "user" else (x.get("target") or ""),
                    "role": role_name(x.get("role")), "from": _("via “{title}”").format(title=x.get("title") or _("Untitled")),
                    "note_id": x.get("note_id")})
    return out


def taken(st):
    """{"user:anna": "edit", "group:team": "view"} - wer schon direkt freigegeben ist"""
    return {f"{x.get('type')}:{x.get('target')}": x.get("role") for x in (st or {}).get("shares") or []}


def hit_rows(d, st, role):
    """Treffer der Personensuche -> [{type, target, name, sub, has}] (Personen, dann eigene Gruppen)"""
    tk, out = taken(st), []
    add_as = _("Add ({role})").format(role=role_name(role))
    for u in (d or {}).get("users") or []:
        t = u.get("username") or ""                              # 2.4 (W4): Handle '~…' (aeltere Server: Benutzername) - nie zeigen
        out.append({"type": "user", "target": t, "name": u.get("name") or t, "sub": _("Person on this server"), "avatar": u.get("avatar"),
                    "has": _("has access") if f"user:{t}" in tk else add_as})
    for g in (d or {}).get("groups") or []:
        n = g.get("name") or ""
        out.append({"type": "group", "target": n, "name": n, "sub": _("Group"),
                    "has": _("has access") if f"group:{n}" in tk else add_as})
    seen = set()
    for r in (d or {}).get("remote") or []:                   # Foederation: Adressbuch (2.3, "Name · host") und name@server
        typ, t = r.get("type") or "remote", r.get("target") or ""
        if typ not in ("remote", "remote_group") or not t or (typ, t) in seen:
            continue
        seen.add((typ, t))
        name = r.get("label") or r.get("name") or t
        sub = _("Person on {host}").format(host=r["host"]) if r.get("label") and r.get("host") else remote_sub(typ)
        out.append({"type": typ, "target": t, "name": name, "sub": sub, "avatar": r.get("avatar"),
                    "has": _("has access") if f"{typ}:{t}" in tk else add_as})
    return out


def remote_info(page):
    """Hinweis unter dem Titel einer Seite einer anderen Instanz - verspricht nur, was die Rolle erlaubt"""
    host = remote_host(page) or "?"
    r = role_of(page)
    if remote_can(page, "db") and RANK.get(r, 0) >= RANK["edit"]:      # Server ab 2.3: fast alles wie lokal
        return _("This page is on {host}. Deleting and sharing are managed by the owner.").format(host=host)
    if RANK.get(r, 0) >= RANK["edit"]:
        return _("This page is on {host}. Here you can read and comment on it and – with edit access – change the "
                 "title and content.").format(host=host)
    if RANK.get(r, 0) >= RANK["comment"]:
        return _("This page is on {host}. Here you can read and comment on it.").format(host=host)
    return _("This page is on {host}. Here you can read it.").format(host=host)


def remote_sub(typ):
    return _("Group on another Saga instance") if typ == "remote_group" else _("Other Saga instance")


def chip_rows(st, role):
    """Leeres Suchfeld: eigene Gruppen als Chips -> [{name, on, tooltip}]"""
    tk = taken(st)
    out = []
    for g in (st or {}).get("groups") or []:
        r = tk.get(f"group:{g}")
        out.append({"name": g, "on": bool(r),
                    "tooltip": _("has access: {role}").format(role=role_name(r)) if r else _("Add ({role})").format(role=role_name(role))})
    return out


def nobody_text(d, q=""):
    """Suche ohne Treffer: Grund vom Server bei name@server (remote_hint, Server 2.2), sonst "Niemand gefunden."
    (aeltere Server ohne Feld: wie bisher). Bei 1-2 Zeichen sucht der Server nur genaue Treffer: das dazusagen."""
    h = (d or {}).get("remote_hint")
    if isinstance(h, str) and h.strip():
        return h.strip()
    if 0 < len((q or "").strip()) < SEARCH_MIN:
        return _("No exact match. Type at least {n} characters to search.").format(n=SEARCH_MIN)
    return _("No one found.")


def short_query(q):
    """"Mindestens 3 Zeichen." nur bei 1-2 eingegebenen Zeichen (leer: kein Hinweis, ab 3: Suche)"""
    return 0 < len((q or "").strip()) < search_min()


def search_hint(st):
    fed = " " + _("Other Saga instances: enter name@server.") if (st or {}).get("federation") else ""
    if (st or {}).get("scope"):
        return _("Only people from your groups. At least 3 characters.") + fed
    return _("At least 3 characters.") + fed


def dialog_notes(st):
    """Hinweise oben im Dialog: abgeschaltet, nicht teilbar (Archiv, Vorlage)"""
    out = []
    if (st or {}).get("enabled") is False:
        out.append(_("The admin has turned off sharing on this server – shares currently have no effect."))
    if (st or {}).get("shareable") is False:
        out.append(_("Archived pages and templates can't be shared."))
    return out


def can_add(st):
    return (st or {}).get("enabled") is not False and (st or {}).get("shareable") is not False


def header_button(page):
    """Knopf "Teilen" in der Kopfleiste (nur Eigentuemer, nur wenn der Server Teilen kennt): {tooltip, on} oder None"""
    if not isinstance(page, dict) or "role" not in page or not is_owner(page):
        return None
    on = bool(page.get("shared"))
    return {"on": on, "tooltip": _("Shared – manage access") if on else _("Share with people or groups")}


# --- Konflikt mit einer anderen Person, Zugriff verloren --------------------------------------------------------
def conflict_texts(by):
    """Konflikt-Dialog, wenn eine andere Person gespeichert hat (409 mit 'by')"""
    return {"heading": _("{name} just changed this page").format(name=by),
            "body": _("Your latest changes are not saved yet."),
            # Translators: conflict with another person (web wording), not the device conflict ("Meine Fassung behalten")
            "reload": _("Load new version"), "keep": pgettext("person conflict", "Keep my version"),
            "ask_heading": _("Overwrite {name}’s changes?").format(name=by),
            "ask_body": _("{name}’s version stays available in the version history.").format(name=by),
            "ask_ok": _("Overwrite")}


def draft_texts():
    return _("Your draft"), _("The new version is loaded. Your unsaved text is below – copy whatever you want to keep.")


def lost_texts(code):
    """Speichern einer fremden Seite abgelehnt: 403 = nur noch lesen, sonst kein Zugriff mehr -> (Titel, Text)"""
    if code == 403:
        return _("View only now"), _("You can no longer edit this page. Your latest text is below – copy it if you want to keep it.")
    return _("No access anymore"), _("You no longer have access to this page. Your latest text is below – copy it if you want to keep it.")


def blocks_text(blocks):
    """Bloecke als Klartext (Absaetze durch Leerzeilen getrennt) - zum Kopieren"""
    out = []

    def walk(arr):
        for b in arr or []:
            if not isinstance(b, dict):
                continue
            d = b.get("data") if isinstance(b.get("data"), dict) else {}
            t = b.get("type")
            if t in ("paragraph", "header", "quote", "callout"):
                s = plain(d.get("text") or "").strip()
                if s:
                    out.append(s)
            elif t == "code":
                if (d.get("code") or "").strip():
                    out.append(d["code"].rstrip())
            elif t == "list":
                lines = []

                def items(its, depth):
                    for it in its or []:
                        if isinstance(it, dict):
                            s = plain(it.get("content") or "").strip()
                            if s:
                                lines.append("  " * depth + s)
                            items(it.get("items"), depth + 1)
                items(d.get("items"), 0)
                if lines:
                    out.append("\n".join(lines))
            elif t == "table":
                rows = ["\t".join(plain(c) for c in r) for r in d.get("content") or [] if isinstance(r, list)]
                if any(r.strip() for r in rows):
                    out.append("\n".join(rows))
            elif t == "toggle":
                s = plain(d.get("title") or "").strip()
                if s:
                    out.append(s)
                walk(d.get("blocks"))
            elif t == "columns":
                for c in d.get("cols") or []:
                    if isinstance(c, dict):
                        walk(c.get("blocks"))
    walk(blocks)
    return "\n\n".join(out)


def doc_text(title, blocks):
    """Eigener Text einer Seite (Titel + Bloecke), wie er gerade im Editor steht"""
    body = blocks_text(blocks)
    title = (title or "").strip()
    return ((title + "\n\n") if title else "") + body if body else title


def snapshot_text(snap):
    """Entwurf (drafts) -> Klartext"""
    doc = (snap or {}).get("doc") or {}
    return doc_text((snap or {}).get("title"), doc.get("blocks") if isinstance(doc, dict) else None)


# =====================================================================================================
# GTK: kleine Bausteine
# =====================================================================================================
def _esc(s):
    return GLib.markup_escape_text(str(s or ""))


def shared_by_markup(tmpl, name):
    """"Shared by {name}" mit fettem Namen (Vorlage und Name werden fuer Pango-Markup maskiert)"""
    a, _sep, b = tmpl.partition("{name}")
    return _esc(a) + "<b>" + _esc(name) + "</b>" + _esc(b)


def people_icon():
    from .publish import _icon
    return _icon("saga-people-symbolic", "system-users-symbolic", "avatar-default-symbolic")


def lock_icon():
    """Schloss (einfarbig): "Kein Zugriff auf diese Seite" """
    from .publish import _icon
    return _icon("saga-lock-symbolic", "changes-prevent-symbolic", "system-lock-screen-symbolic")


def comment_icon():
    from .publish import _icon
    return _icon("saga-comment-symbolic", "mail-message-new-symbolic")


def shared_by_box(page):
    """Etikett unter dem Titel einer fremden Seite: [Personen-Symbol] Geteilt von <Name> · [Du kannst …] - kein Knopf"""
    sb = shared_by(page)
    if sb is None:
        return None
    tmpl, name, you = sb
    box = Gtk.Box(spacing=6)
    box.add_css_class("hk-sharedby")
    box.add_css_class("hk-pagemeta")
    ic = Gtk.Image.new_from_icon_name(people_icon())
    ic.add_css_class("dim-label")
    box.append(ic)
    lb = Gtk.Label(xalign=0, wrap=True, use_markup=True)
    lb.set_markup(shared_by_markup(tmpl, name))
    lb.add_css_class("dim-label")
    box.append(lb)
    if you:
        sep = Gtk.Label(label="·")
        sep.add_css_class("dim-label")
        box.append(sep)
        pill = Gtk.Label(label=you)
        pill.add_css_class("hk-role-pill")
        pill.add_css_class(f"hk-role-{role_of(page)}")
        hint = role_hint(page)
        if hint:
            pill.set_tooltip_text(hint)
        box.append(pill)
    box.update_property([Gtk.AccessibleProperty.LABEL], [tmpl.format(name=name) + (" · " + you if you else "")])
    return box


def avatar(typ, name, size=32, url=None):
    """Kreis fuer Person/Gruppe; url: Profilbild (Feld "avatar", Server ab 2.1) ueber die Initialen"""
    grp = typ in ("group", "remote_group")
    from .avatars import initials_text
    text = initials_text(name)                                 # "Bert (bert@b.example)" -> Initialen "B", nicht "B("
    a = Adw.Avatar(size=size, text=text, show_initials=not grp)
    if grp:
        a.set_icon_name(people_icon())
    elif url:
        from . import avatars
        avatars.attach(a, url, size)
    return a


def _row(title, subtitle="", activatable=False):
    r = Adw.ActionRow(title=title, subtitle=subtitle, activatable=activatable)
    r.set_use_markup(False)                         # Namen und Titel sind Text, kein Markup
    return r


TEXT_MAX_SHARE = 0.4            # Textfeld im Zustandsdialog: hoechstens ~40 % der Fensterhoehe
TEXT_MIN_PX = 72


class TextDialog(Adw.AlertDialog):
    """Zustandsdialog mit Text zum Kopieren (Entwurf nach "Neue Version laden", "Nur noch lesen", "Kein Zugriff mehr"),
    einheitlich wie Web, iOS und Android: Titel, kurzer Text, Textfeld (nur so hoch wie noetig, hoechstens ~40 % der
    Fensterhoehe), Hauptknopf "Kopieren" (kopiert und schliesst, Meldung im Fenster), zweiter Knopf "Schliessen" (Esc)
    - kein zusaetzliches × in einer Kopfleiste."""

    def __init__(self, title, intro, text, on_closed=None):
        super().__init__(heading=title, body=intro)
        self.text = text or ""
        self._parent = None
        self.scroll = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, propagate_natural_height=True,
                                         min_content_height=TEXT_MIN_PX)
        self.scroll.add_css_class("card")
        self.scroll.add_css_class("hk-textdlg-box")
        self.view = Gtk.TextView(editable=False, cursor_visible=False, wrap_mode=Gtk.WrapMode.WORD_CHAR, top_margin=10,
                                 bottom_margin=10, left_margin=12, right_margin=12)
        self.view.add_css_class("hk-textdlg")
        self.view.get_buffer().set_text(self.text)
        self.view.update_property([Gtk.AccessibleProperty.LABEL], [_("Your text")])
        self.scroll.set_child(self.view)
        self.set_extra_child(self.scroll)
        self.add_response("close", _("Close"))
        self.add_response("copy", _("Copy"))
        self.set_response_appearance("copy", Adw.ResponseAppearance.SUGGESTED)
        self.set_default_response("copy")
        self.set_close_response("close")
        if hasattr(self, "set_prefer_wide_layout"):
            self.set_prefer_wide_layout(True)
        self.connect("response", lambda _d, r: r == "copy" and self.copy())
        if on_closed:
            self.connect("closed", lambda *_: on_closed())

    def present(self, parent=None):
        self._parent = parent
        h = parent.get_height() if parent is not None else 0
        self.scroll.set_max_content_height(max(TEXT_MIN_PX * 2, int((h or 800) * TEXT_MAX_SHARE)))
        Adw.AlertDialog.present(self, parent)

    def copy(self):
        from gi.repository import Gdk
        Gdk.Display.get_default().get_clipboard().set(self.text)
        win = self._parent.get_root() if self._parent is not None else None
        if hasattr(win, "toast"):
            win.toast(_("Text copied"))


# =====================================================================================================
# Teilen-Dialog
# =====================================================================================================
def open_share(win, pid, on_change=None):
    """Seitenmenue "Teilen …" bzw. Knopf in der Kopfleiste: Freigaben holen, dann den Dialog zeigen"""
    bg(client.get, f"api/notes/{pid}/shares", done=lambda st: ShareDialog(win, pid, st, on_change).present(win),
       error=lambda e: win.toast(_("Not loaded: {err}").format(err=e)))


class ShareDialog(Adw.Dialog):
    """Adw.Dialog: Suche (Personen ab 3 Zeichen, eigene Gruppen als Chips) + Rolle mit Erklaerung, Liste "Zugriff" mit
    tatsaechlicher Rolle, Rolle aendern, Entfernen mit Rueckfrage; Freigaben der Elternseiten (nur lesend); Link."""

    def __init__(self, win, pid, st, on_change=None):
        super().__init__(title=_("Share"), content_width=580, content_height=720)
        self.win, self.pid, self.st, self.on_change = win, pid, st or {}, on_change
        self.seq = 0
        self._tid = 0
        self.last_hits = None
        self._rows = {}                     # Gruppe -> hinzugefuegte Zeilen (zum Leeren)
        self.toasts = Adw.ToastOverlay()
        tv = Adw.ToolbarView()
        tv.add_top_bar(Adw.HeaderBar())
        self.page = Adw.PreferencesPage()
        tv.set_content(self.page)
        self.toasts.set_child(tv)
        self.set_child(self.toasts)
        self.connect("closed", lambda *_: self._cancel())
        from .publish import note_label, set_status, status_label
        self._set_status = set_status
        # Hinweise (abgeschaltet, nicht teilbar)
        self.g_notes = Adw.PreferencesGroup()
        self.l_notes = note_label("warning")
        self.g_notes.add(self.l_notes)
        self.page.add(self.g_notes)
        # Suche: Hinweis/Fehler direkt unter dem Feld, bei leerem Feld die eigenen Gruppen als Chips
        g = Adw.PreferencesGroup()
        self.q = Adw.EntryRow(title=_("Search for a person or group"))
        self.q.set_input_hints(Gtk.InputHints.NO_SPELLCHECK | Gtk.InputHints.NO_EMOJI)
        try:
            self.q.get_delegate().set_max_length(60)
        except (AttributeError, TypeError):
            pass
        self.q.connect("changed", lambda *_: self._search())
        self.q.connect("entry-activated", lambda *_: self._first_hit())
        g.add(self.q)
        self.status = status_label()
        self.status.set_margin_top(GAP)
        g.add(self.status)
        self.page.add(g)
        self.g_search = g
        # Rolle fuer neue Freigaben (mit Erklaerung als Untertitel)
        self.g_role = Adw.PreferencesGroup()
        self.role = Adw.ComboRow(title=_("Role for new share"), model=Gtk.StringList.new([role_name(r) for r in ROLES]))
        self.role.connect("notify::selected", lambda *_: self._role_changed())
        self.g_role.add(self.role)
        self.page.add(self.g_role)
        # Treffer
        self.g_hits = Adw.PreferencesGroup()
        self.page.add(self.g_hits)
        # Eigene Gruppen als Chips (bei leerem Suchfeld)
        self.chips = Gtk.Box(spacing=6)
        self.chips.add_css_class("hk-share-chips")
        self.chips_flow = Gtk.FlowBox(selection_mode=Gtk.SelectionMode.NONE, max_children_per_line=6, row_spacing=4,
                                      column_spacing=4, hexpand=True)
        self.chips_label = Gtk.Label(label=_("Your groups:"), xalign=0, valign=Gtk.Align.CENTER)
        self.chips_label.add_css_class("dim-label")
        self.chips.append(self.chips_label)
        self.chips.append(self.chips_flow)
        self.chips.set_margin_top(GAP)
        g.add(self.chips)
        # Zugriff
        self.g_access = Adw.PreferencesGroup(title=_("Access"), description=_("Subpages and entries are included."))
        self.page.add(self.g_access)
        # Ueber uebergeordnete Seiten (nur lesend)
        self.g_inh = Adw.PreferencesGroup(title=_("Through parent pages"),
                                          description=_("These shares also apply here. You can change them on the parent page."))
        self.page.add(self.g_inh)
        # Link
        from .publish import UrlRow, icon_button
        gl = Adw.PreferencesGroup()
        self.link = UrlRow(_("Link"))
        self.link.add_button(icon_button("edit-copy-symbolic", _("Copy link"), self._copy_link))
        self.link.set_url(store.page_url(pid))
        self.link.set_note(_("The link opens the page for everyone who has access."))   # Hinweis als Untertitel der Zeile
        gl.add(self.link)
        self.page.add(gl)
        self.draw()
        self._hint()
        GLib.idle_add(lambda: (self.q.get_sensitive() and self.q.grab_focus(), False)[1])

    # --- Hilfen -----------------------------------------------------------------------------------------
    @property
    def path(self):
        return f"api/notes/{self.pid}/shares"

    @property
    def cur_role(self):
        i = self.role.get_selected()
        return ROLES[i] if 0 <= i < len(ROLES) else "view"

    def toast(self, text):
        if text:
            self.toasts.add_toast(Adw.Toast(title=_esc(text), timeout=3))

    def _clear(self, group):
        for r in self._rows.pop(group, []):
            group.remove(r)

    def _add(self, group, row):
        group.add(row)
        self._rows.setdefault(group, []).append(row)
        return row

    def _cancel(self):
        self.seq += 1
        if self._tid:
            GLib.source_remove(self._tid)
            self._tid = 0

    def _copy_link(self):
        self.win.copy_text(store.page_url(self.pid))
        self.toast(_("Link copied"))

    def _hint(self, text=None, error=False):
        """Rueckmeldung direkt unter dem Suchfeld: ohne Text "Mindestens 3 Zeichen." nur bei 1-2 Zeichen (als Fehler am
        Feld), sonst nichts; mit Text (Fehler, "Niemand gefunden.") diesen"""
        from .publish import mark_error
        if text is None:
            text = search_hint(self.st) if short_query(self.q.get_text()) else ""
            error = bool(text)
            if not text and not self.q.get_text().strip() and self.st.get("federation") and can_add(self.st):
                text = _("Other Saga instances: enter name@server.")       # Foederation: Hinweis bei leerem Feld
        self._set_status(self.status, text, False if error else None)
        (self.status.remove_css_class if error else self.status.add_css_class)("dim-label")
        mark_error(self.q, error)

    # --- Zeichnen ---------------------------------------------------------------------------------------
    def draw(self):
        st = self.st
        notes = dialog_notes(st)
        self.l_notes.set_label("\n".join(notes))
        self.g_notes.set_visible(bool(notes))
        ok = can_add(st)
        self.q.set_sensitive(ok)
        self.role.set_sensitive(ok)
        self._role_changed(redraw=False)
        self._draw_access()
        self._draw_inherited()
        self._draw_chips()
        if self.last_hits is not None and len(self.q.get_text().strip()) >= search_min():
            self._draw_hits(self.last_hits)

    def _role_changed(self, redraw=True):
        self.role.set_subtitle(role_help(self.cur_role))
        if redraw:
            self._draw_chips()
            if self.last_hits is not None and len(self.q.get_text().strip()) >= search_min():
                self._draw_hits(self.last_hits)

    def _draw_access(self):
        g = self.g_access
        self._clear(g)
        doc = next((d for d in list(store.docs) if d.id == self.pid and d.fetched), None)
        oname = owner_name(doc.page) if doc is not None else ""
        me = self._add(g, _row(_("{name} (you)").format(name=oname) if oname else _("You"), _("Owner")))
        me.add_prefix(avatar("user", oname or _("You"), url=((doc.page if doc is not None else {}).get("owner") or {}).get("avatar")))
        rows = share_rows(self.st)
        for x in rows:
            r = self._add(g, _row(x["name"], x["sub"] + (("\n" + x["via"]) if x["via"] else "")))
            r.set_subtitle_lines(3)
            r.add_prefix(avatar(x["type"], x["name"], url=x.get("avatar")))
            dd = Gtk.DropDown.new_from_strings([role_name(k) for k in ROLES])
            dd.set_valign(Gtk.Align.CENTER)
            dd.set_selected(ROLES.index(x["role"]) if x["role"] in ROLES else 0)
            dd.update_property([Gtk.AccessibleProperty.LABEL], [x["a11y_role"]])
            dd.set_tooltip_text(x["a11y_role"])
            dd.connect("notify::selected", lambda d, _p, x=x: self._change_role(x, ROLES[d.get_selected()]))
            r.add_suffix(dd)
            rm = Gtk.Button(icon_name="window-close-symbolic", valign=Gtk.Align.CENTER, tooltip_text=_("Remove access"))
            rm.add_css_class("flat")
            rm.update_property([Gtk.AccessibleProperty.LABEL], [x["a11y_remove"]])
            rm.connect("clicked", lambda *_, x=x: self._ask_remove(x))
            r.add_suffix(rm)
        if not rows:
            e = self._add(g, _row(_("Not shared with anyone yet. Search for a person or group above.")))
            e.add_css_class("dim-label")

    def _draw_inherited(self):
        g = self.g_inh
        self._clear(g)
        rows = inherited_rows(self.st)
        g.set_visible(bool(rows))
        for x in rows:
            r = self._add(g, _row(x["name"], x["sub"]))
            r.add_prefix(avatar(x["type"], x["name"], url=x.get("avatar")))
            lb = Gtk.Label(label=x["role"], valign=Gtk.Align.CENTER)
            lb.add_css_class("hk-role-pill")
            r.add_suffix(lb)
            b = Gtk.Button(label=x["from"], valign=Gtk.Align.CENTER, tooltip_text=_("Go to the parent page"))
            b.add_css_class("flat")
            b.get_child().set_ellipsize(Pango.EllipsizeMode.END)
            b.get_child().set_max_width_chars(22)
            b.connect("clicked", lambda *_, nid=x["note_id"]: self._go_parent(nid))
            r.add_suffix(b)

    def _draw_chips(self):
        while (c := self.chips_flow.get_first_child()):
            self.chips_flow.remove(c)
        chips = chip_rows(self.st, self.cur_role)
        show = bool(chips) and not self.q.get_text().strip() and can_add(self.st)
        self.chips.set_visible(show)
        for c in chips:
            b = Gtk.Button(tooltip_text=c["tooltip"])
            bx = Gtk.Box(spacing=4)
            bx.append(Gtk.Image.new_from_icon_name(people_icon()))
            bx.append(Gtk.Label(label=c["name"]))
            if c["on"]:                                      # hat schon Zugriff: ✓ im Chip
                ck = Gtk.Image.new_from_icon_name("object-select-symbolic")
                ck.add_css_class("hk-chip-check")
                bx.append(ck)
            b.set_child(bx)
            b.add_css_class("hk-chip-btn")
            if c["on"]:
                b.add_css_class("hk-on")
            b.update_property([Gtk.AccessibleProperty.LABEL], [c["name"] + " – " + c["tooltip"]])
            b.connect("clicked", lambda *_, n=c["name"]: self._put("group", n, self.cur_role, n))
            self.chips_flow.append(b)

    def _draw_hits(self, d):
        g = self.g_hits
        self._clear(g)
        hits = hit_rows(d, self.st, self.cur_role)
        g.set_visible(bool(hits))
        for h in hits:
            r = self._add(g, _row(h["name"], h["sub"], activatable=True))
            r.add_prefix(avatar(h["type"], h["name"], url=h.get("avatar")))
            lb = Gtk.Label(label=h["has"], valign=Gtk.Align.CENTER)
            lb.add_css_class("dim-label")
            r.add_suffix(lb)
            r.connect("activated", lambda *_, h=h: self._pick(h))
        if not hits and len(self.q.get_text().strip()) >= search_min():
            self._hint(nobody_text(d, self.q.get_text()), True)

    # --- Suchen -----------------------------------------------------------------------------------------
    def _search(self):
        q = self.q.get_text().strip()
        self._cancel()
        self._hint()
        self._draw_chips()
        if len(q) < search_min():
            self.last_hits = None
            self._clear(self.g_hits)
            self.g_hits.set_visible(False)
            return
        my = self.seq

        def done(d):
            if my == self.seq and len(self.q.get_text().strip()) >= search_min():
                self.last_hits = d
                self._draw_hits(d)

        def err(e):
            if my == self.seq:
                self._hint(str(e), True)

        def go():
            self._tid = 0
            bg(client.get, "api/share/targets?q=" + GLib.Uri.escape_string(q, None, False), done=done, error=err)
            return False
        self._tid = GLib.timeout_add(SEARCH_DELAY_MS, go)

    def _first_hit(self):
        hits = hit_rows(self.last_hits, self.st, self.cur_role) if self.last_hits else []
        if hits and len(self.q.get_text().strip()) >= search_min():
            self._pick(hits[0])

    def _pick(self, h):
        self.q.set_text("")
        self.last_hits = None
        self._clear(self.g_hits)
        self.g_hits.set_visible(False)
        self._put(h["type"], h["target"], self.cur_role, h["name"])
        self.q.grab_focus()

    # --- Aendern ----------------------------------------------------------------------------------------
    def _sync(self, ns):
        self.st = ns or {}
        has = bool(self.st.get("shares"))
        p = store.by_id.get(self.pid)
        if p is not None and bool(p.get("shared")) != has:
            p["shared"] = has
            store.emit("pages-changed")
        for d in list(store.docs):
            if d.id == self.pid and d.fetched:
                d.page["shared"] = has
        if self.on_change:
            self.on_change()
        self.draw()

    def _put(self, typ, target, role, name, done_text=None):
        def ok(ns):
            self._sync(ns)
            self.toast(done_text or _("✓ Shared with {name} ({role})").format(name=name, role=role_name(role)))

        def err(e):
            self._hint(str(e), True)
            self.draw()                                    # Auswahl der Rolle zuruecksetzen
        bg(client.json, "PUT", self.path, {"type": typ, "target": target, "role": role}, done=ok, error=err)

    def _change_role(self, x, role):
        if role == x["role"]:
            return
        self._put(x["type"], x["target"], role, x["name"], _("✓ Role changed"))

    def _ask_remove(self, x):
        from .publish import confirm
        confirm(self, _("Remove access?"), x["remove_body"], _("Remove"), lambda: self._remove(x))

    def _remove(self, x):
        def ok(ns):
            self._sync(ns)
            self.toast(_("✓ Access removed"))
        q = "?type=" + GLib.Uri.escape_string(x["type"], None, False) + "&target=" + GLib.Uri.escape_string(x["target"], None, False)
        bg(client.json, "DELETE", self.path + q, done=ok, error=lambda e: self.toast(str(e)))

    def _go_parent(self, nid):
        if not nid:
            return
        win = self.win
        self.close()
        win.show_page(nid)
        open_share(win, nid, self.on_change)
