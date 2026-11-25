# 2.6 (Bugs-DB 2027a-7): Adressbuch (contacts2) - Bildschirmfotos mit nachgebautem Server (tests/mockv25.py), ohne Netz.
#   HK_TEST=tests/kontakte2shots.py HK_OUT=<ordner> HK_SHOT_PREFIX=nachher- python3 -m hlidskjalf.main
# Laeuft auch mit dem Stand vor 2027a-7 (Vorher-Bilder: nur die Einstellungen, ohne "Kontakte"). Nur Demo-Daten.
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(os.environ["HK_TEST"])))
import mockserver  # noqa: E402

P = os.environ.get("HK_SHOT_PREFIX", "")
AFTER = P.startswith("nachher")
DE = os.environ.get("LANG", "de").startswith("de")
S = {}


def check(name, ok):
    print(f"{'OK  ' if ok else 'FEHLER'} {name}", flush=True)


def shoot(g, name):
    g["shot"](os.path.join(g["out"], f"{P}{name}.png"))


def _dark(on):
    from gi.repository import Adw
    Adw.StyleManager.get_default().set_color_scheme(Adw.ColorScheme.FORCE_DARK if on else Adw.ColorScheme.FORCE_LIGHT)


def _dlg(g):
    return g["win"].get_visible_dialog()


def _has():
    import importlib.util
    return importlib.util.find_spec("hlidskjalf.contactbook") is not None


def start(g):
    srv = S["srv"] = mockserver.install()
    srv.v25 = True
    srv.extra_features = ["contacts1", "contacts2"]
    srv.contacts_on = True
    srv.book_on = True
    _dark(False)
    g["store"].logged_in()
    g["store"].check_version()
    g["win"].set_default_size(1100, 860)


def data(g):
    srv = S["srv"]
    if not hasattr(srv, "book"):
        return
    import time
    from mockv25 import book_add
    now = time.time()
    S["anna"] = book_add(srv, "Anna Berg", saga={"ref": "~annaberg"}, last_at=now - 600, form="informal", salutation="Liebe Anna",
                         addrs=[("e164", "+491705550123", "mobile"), ("email", "anna.berg@example.org", "work")])
    book_add(srv, "Bert Gast", saga={"ref": "~bertgast@b.example", "remote": True, "host": "b.example"}, last_at=now - 7200)
    book_add(srv, "Mia Sommer", addrs=[("e164", "+493055501234", "home")], last_at=None)
    book_add(srv, "Klaus Weber", form="formal", salutation="Sehr geehrter Herr Weber",
             addrs=[("email", "k.weber@example.com", "work"), ("e164", "+498955507788", "work")], last_at=None)
    for i, c in enumerate(sorted(srv.book.values(), key=lambda c: c["id"])):
        c["updated"] = now - 86400 * (i + 2)


def open_settings(g):
    g["win"].settings_dialog(page="settings")


def show_row(g):
    """Zeile "Kontakte" (vorher: "Mitteilungen" direkt darueber) in den sichtbaren Bereich holen"""
    d = _dlg(g)
    rows = [w for w in _walk(d.pages["settings"]) if getattr(w, "hk_sub", None) in ("contacts", "push") and w.get_visible()]
    rows.sort(key=lambda w: w.hk_sub != "contacts")
    if rows:
        rows[0].grab_focus()


def shot_settings(g):
    shoot(g, "01-einstellungen-hell")
    if AFTER:
        d = _dlg(g)
        p = d.pages.get("contacts") if d is not None else None
        check("Einstellungen: Zeile Kontakte sichtbar", p is not None and p.get_visible())
    _dark(True)


def shot_settings_dark(g):
    shoot(g, "02-einstellungen-dunkel")
    _dark(False)
    if AFTER and _has():
        _dlg(g).show_page("contacts")


def shot_list(g):
    if not (AFTER and _has()):
        return
    shoot(g, "03-liste-hell")
    page = _dlg(g).pages["contacts"]
    check("Liste: 4 Kontakte, Anna zuerst", len(page.rows) == 4 and page.rows[0].get_title() == "Anna Berg")
    if DE:
        check("Landesvorwahl vorgeschlagen (+49)", page.cc_hint.get_visible() and "+49" in page.cc_hint.get_title())
    _dark(True)


def shot_list_dark(g):
    if not (AFTER and _has()):
        return
    shoot(g, "04-liste-dunkel")
    _dark(False)
    S["det"] = _dlg(g).pages["contacts"].open(S["anna"])


def shot_detail(g):
    if not (AFTER and _has()):
        return
    shoot(g, "05-detail-hell")
    det = S["det"]
    check("Detail: Nummer mit tel:", any(getattr(r, "hk_href", None) == "tel:+491705550123" for r in _walk(det.page)))
    _dark(True)


def shot_detail_dark(g):
    if not (AFTER and _has()):
        return
    shoot(g, "06-detail-dunkel")
    _dark(False)
    S["det"].edit()


def shot_edit(g):
    if not (AFTER and _has()):
        return
    shoot(g, "07-bearbeiten-hell")
    _dark(True)


def shot_edit_dark(g):
    if not (AFTER and _has()):
        return
    shoot(g, "08-bearbeiten-dunkel")
    _dark(False)
    d = _dlg(g)
    d.pop_subpage()
    d.pop_subpage()
    from hlidskjalf.contactbook import ContactEditor
    S["new"] = ContactEditor(d.pages["contacts"].host, None).show()


def shot_new(g):
    if not (AFTER and _has()):
        return
    shoot(g, "09-neuer-kontakt")
    d = _dlg(g)
    if d is not None:
        d.force_close()


def _walk(w):
    out, c = [w], w.get_first_child()
    while c:
        out += _walk(c)
        c = c.get_next_sibling()
    return out


steps = [(300, start), (1500, data), (300, open_settings), (1200, show_row), (1200, shot_settings), (900, shot_settings_dark), (1500, shot_list),
         (900, shot_list_dark), (1500, shot_detail), (900, shot_detail_dark), (1200, shot_edit), (900, shot_edit_dark),
         (1500, shot_new)]
