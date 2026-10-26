"""Einstellungen 2.5: Konto · Einstellungen · Info, Unterseiten, Update-Hinweis unter Info (ohne Netz)."""
import json
import threading

from gi.repository import Adw

from hk_helpers import pump

PAGE = "https://github.com/yggdrasil-sh/saga-apps/releases/tag/v2.5.0-rc.140"


def _walk(w):
    c = w.get_first_child()
    while c:
        yield c
        yield from _walk(c)
        c = c.get_next_sibling()


def _hint(monkeypatch, label="2.5.0-rc.130", result=None, fail=False, gate=None):
    from hlidskjalf import updatehint as U
    from hlidskjalf.config import settings
    from hlidskjalf.updatecheck import UpdateHintModel
    import hlidskjalf.updatecheck as uc
    settings.set("update_hint", None)
    settings.set("update_hint_on", None)
    h = UpdateHintModel()
    h.label_fn = lambda: label
    calls = []

    def fetch(url, ch):
        calls.append((url, ch, threading.current_thread() is threading.main_thread()))
        if gate is not None:
            gate.wait(5)
        if fail:
            raise OSError("offline")
        return U.parse(json.dumps(result or {"format": 1, "channel": "testing", "version": "2.5.0-rc.140", "page": PAGE, "assets": {}}), ch)
    h.fetch = fetch
    monkeypatch.setattr(uc, "hint", h)
    return h, calls


def test_three_pages_and_sections(win, srv, monkeypatch):
    _hint(monkeypatch)
    from hlidskjalf.settings import AppsGroup, CalendarGroup, ImmichGroup, InfoPage, SettingsDialog
    from hlidskjalf.notifications import NotificationSettingsGroup
    d = SettingsDialog(win)
    d.present(win)
    pump(None, 0.5)
    tops = [c for c in _walk(d) if isinstance(c, Adw.PreferencesPage) and c.get_parent() is not None
            and c in (d.pages["account"], d.pages["settings"], d.pages["info"])]
    assert [p.get_title() for p in tops] == ["Account", "Settings", "Info"]
    acc, st = d.pages["account"], d.pages["settings"]
    assert any(isinstance(x, AppsGroup) for x in _walk(acc)) and d.server.get_ancestor(Adw.PreferencesPage) is acc
    for cls in (ImmichGroup, CalendarGroup, NotificationSettingsGroup):
        assert any(isinstance(x, cls) for x in _walk(st)), cls
    assert d.lang.get_ancestor(Adw.PreferencesPage) is st
    assert isinstance(d.pages["info"], InfoPage)
    # Sprung zu einer Unterseite: Mitteilungen (unter Einstellungen) und zurueck auf eine Hauptseite
    d.show_page("push")
    assert pump(lambda: d.sub == "push" and d.pages["push"].get_mapped(), 3)
    d.show_page("info")
    assert pump(lambda: d.sub is None and d.get_visible_page() is d.pages["info"], 3)
    d.show_page("push")                                                   # zweimal: Seite wird wiederverwendet
    assert pump(lambda: d.sub == "push" and d.pages["push"].get_mapped(), 3)
    d.show_page("general")                                                # alter Name
    assert pump(lambda: d.get_visible_page() is st and d.sub is None, 3)
    d.force_close()


def test_info_versions_and_manual_check(win, srv, monkeypatch):
    gate = threading.Event()
    h, calls = _hint(monkeypatch, gate=gate)
    from hlidskjalf.store import store
    from hlidskjalf.settings import SettingsDialog
    store.server_version = "2.5.0"
    opened = []
    win.open_uri = lambda u: opened.append(u)
    d = SettingsDialog(win)
    d.present(win)
    d.show_page("info")
    info = d.pages["info"]
    assert pump(lambda: info.get_mapped(), 3)
    assert info.srv_row.get_subtitle() == "Saga 2.5.0" and info.ch_row.get_subtitle() == "Test version"
    # Oeffnen von Info: automatische Pruefung (nie geprueft) - im Hintergrund, Schaltflaeche "Pruefe …"
    assert pump(lambda: calls, 3)
    assert calls[0][0].endswith("/appcast/testing.json") and calls[0][1] == "testing" and calls[0][2] is False
    assert pump(lambda: info.btn.get_label() == "Checking …" and not info.btn.get_sensitive(), 3)
    gate.set()
    assert pump(lambda: info.btn_state == "update", 5)
    assert info.btn.get_label() == "Update" and info.btn.has_css_class("suggested-action")
    assert info.st_row.get_title() == "Saga 2.5.0-rc.140 is available" and info.st_row.get_subtitle().startswith("Last checked:")
    texts = [getattr(x, "get_label", lambda: "")() or "" for x in _walk(info)] + \
        [getattr(x, "get_subtitle", lambda: "")() or "" for x in _walk(info) if isinstance(x, Adw.ActionRow)]
    assert not any("https://" in t for t in texts)                       # nie eine URL als Text
    info.btn.emit("clicked")
    assert opened == [PAGE]
    from hlidskjalf.config import settings
    assert settings.get("update_hint")["release"]["version"] == "2.5.0-rc.140"
    # Schalter aus: keine Schaltflaeche, keine Anfrage
    n = len(calls)
    info.sw.set_active(False)
    assert pump(lambda: not info.st_row.get_visible(), 2)
    h.check(manual=True)
    h.info_opened()
    pump(None, 0.3)
    assert len(calls) == n and settings.get("update_hint_on") is False
    d.force_close()


def test_up_to_date_and_failed_only_after_manual(win, srv, monkeypatch):
    h, calls = _hint(monkeypatch, label="2.5.0", result={"format": 1, "channel": "stable", "version": "2.5.0", "page": PAGE})
    from hlidskjalf.settings import SettingsDialog
    d = SettingsDialog(win)
    d.present(win)
    d.show_page("info")
    info = d.pages["info"]
    assert pump(lambda: info.st_row.get_title() == "You have the latest version." and info.btn_state == "check", 5)
    assert info.btn.get_label() == "Check for updates" and info.ch_row.get_subtitle() == "Stable"
    # automatisch fehlgeschlagen: still
    h.fetch = lambda url, ch: (_ for _ in ()).throw(OSError("offline"))
    h.checked = None
    h._auto(0)
    pump(lambda: not h.checking, 3)
    assert not h.failed and info.st_row.get_title() != "Check failed"
    info.btn.emit("clicked")                                              # manuell: sichtbar
    assert pump(lambda: info.st_row.get_title() == "Check failed", 3)
    assert info.btn.get_label() == "Check for updates"
    d.force_close()


def test_dev_build_no_check(win, srv, monkeypatch):
    h, calls = _hint(monkeypatch, label=None)
    from hlidskjalf.settings import SettingsDialog
    d = SettingsDialog(win)
    d.present(win)
    d.show_page("info")
    info = d.pages["info"]
    assert pump(lambda: info.get_mapped(), 3)
    pump(None, 0.3)
    assert calls == [] and not info.st_row.get_visible() and info.ch_row.get_subtitle() == "Own build"
    h.app_started()
    pump(None, 0.2)
    assert calls == []
    d.force_close()


def test_12h_cache_on_start(monkeypatch):
    import time
    h, calls = _hint(monkeypatch)
    h.checked = time.time() - 3600                                        # vor 1 h geprueft: beim Start nicht erneut
    h.app_started()
    assert calls == [] and not h.checking
    h.checked = time.time() - 13 * 3600
    h.app_started()
    assert pump(lambda: calls and not h.checking, 3)
