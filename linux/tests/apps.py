# Einstellungen -> Konto: eine Liste "Apps & Geraete" (Geraete mit Schluessel, weitere Schluessel eingeklappt)
S = {}


def _children(w):
    out, c = [], w.get_first_child()
    while c:
        out.append(c)
        out += _children(c)
        c = c.get_next_sibling()
    return out


def s1(g):
    from hlidskjalf.settings import SettingsDialog
    d = SettingsDialog(g["win"])
    d.present(g["win"])
    S["d"] = d


def s2(g):
    from gi.repository import Adw
    from hlidskjalf.settings import AppsGroup
    d = S["d"]
    pages = [c for c in _children(d) if isinstance(c, Adw.PreferencesPage)]
    acc = next((p for p in pages if p.get_icon_name() == "avatar-default-symbolic"), None)   # Seite "Konto" (sprachunabhaengig)
    if acc:
        d.set_visible_page(acc)
    grp = next((c for c in _children(d) if isinstance(c, AppsGroup)), None)
    print("Gruppe:", grp.get_title() if grp else None, "Zeilen:", len(grp.rows) if grp else 0, flush=True)
    print("Titel:", [r.get_title() for r in (grp.rows if grp else []) if hasattr(r, "get_title")], flush=True)
    if grp and acc:
        for c in _children(acc):
            if c.__class__.__name__ == "ScrolledWindow":
                a = c.get_vadjustment()
                a.set_value(a.get_upper() - a.get_page_size())


def s3(g):
    g["shot"](g["out"] + "/01-apps.png")
    S["d"].close()


steps = [(4000, s1), (3500, s2), (800, s3)]
