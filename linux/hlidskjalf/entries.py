"""Eintraege anlegen (Saga 2.5, Fix-Runde 27.10.) - reine Logik ohne GTK (tests/test_fix27.py).

- #11 Lesen/Kommentieren: Anlege-Schaltflaechen bleiben sichtbar, aber ausgegraut mit Hinweis
  (Schaltflaechen-Block "Neuer Eintrag", "Neu" der Datenbank).
- E2 Startseite als Kontoeinstellung start_page (GET/PATCH api/account), sonst wie bisher nur auf diesem Geraet.
- E4 Eintrag aus einer Vorlage: leerer Titel + Platzhalter (duplicate liefert fresh/placeholder); unveraendert
  geschlossen -> POST api/notes/{id}/discard {"fresh"} (endgueltig, der Server prueft "unveraendert" selbst)."""
from .i18n import _


def blocked_button_hint(action, read_only):
    """Schaltflaechen-Block: Hinweis (Tooltip) fuer eine ausgegraute Schaltflaeche oder None (klickbar).
    Nur "Neuer Eintrag" (action db) braucht Bearbeiten; Seite oeffnen und eigene Aufgabe anlegen gehen immer."""
    if read_only and action == "db":
        return _("You can only view this page – new entries can’t be created here.")
    return None


def blocked_new_hint(readonly):
    """"Neu" einer Datenbank: Hinweis fuer die ausgegraute Schaltflaeche bei Lesen/Kommentieren oder None"""
    if readonly:
        return _("You can only view this database – new entries can’t be created here.")
    return None


# --- E2 eigene Startseite (Kontoeinstellung start_page, Server ab 2.5) -------------------------------------------------
def account_start(acc):
    """GET api/account -> (bekannt, Seiten-ID oder None). bekannt=False: aelterer Server ohne start_page (dann bleibt
    die Startseite eine Einstellung dieses Geraets). Ungueltiges (bool, Text, <= 0) zaehlt wie null."""
    if not isinstance(acc, dict) or "start_page" not in acc:
        return False, None
    v = acc.get("start_page")
    if isinstance(v, bool) or not isinstance(v, int) or v <= 0:
        return True, None
    return True, v


def can_set_home(owner_ok, remote, pid, server_home):
    """Seitenmenue "Als Startseite festlegen": eigene Seiten immer (auch lokal), geteilte nur mit Server-Einstellung;
    Seiten anderer Server (negative IDs) nicht (Etappe 1)"""
    if remote or not isinstance(pid, int) or pid <= 0:
        return False
    return bool(owner_ok or server_home)


def start_switch(start, opened, still_initial, visible):
    """Konto geladen: Route, zu der jetzt gewechselt wird, oder None. Nur solange noch die Startansicht offen ist (die
    Person hat nichts anderes geoeffnet); eine Startseite ohne Zugriff zaehlt wie keine."""
    if not still_initial:
        return None
    want = ("page", start) if start and visible(start) else ("today",)
    return None if want == opened else want


# --- E4 neue Eintraege aus einer Vorlage -----------------------------------------------------------------------------
def fresh_info(resp):
    """Antwort von POST api/notes/{vorlage}/duplicate -> {"tok", "placeholder"} oder None (aelterer Server, keine Vorlage
    in eine Datenbank)"""
    if not isinstance(resp, dict):
        return None
    tok = resp.get("fresh")
    if not isinstance(tok, str) or not 8 <= len(tok) <= 64:
        return None
    ph = resp.get("placeholder")
    return {"tok": tok, "placeholder": ph.strip()[:200] if isinstance(ph, str) else ""}


def discard_ok(fresh, fetched, title, edits, dirty, saving, read_only=False):
    """Beim Schliessen endgueltig verwerfen? Nur ein eben aus einer Vorlage angelegter Eintrag (fresh), sauber geladen,
    Titel leer und in dieser Ansicht nichts geaendert (keine Eingabe, nichts offen zum Speichern). Ob Eigenschaften/Inhalt
    noch der Vorlage entsprechen, prueft der Server zusaetzlich (409 -> Eintrag bleibt)."""
    return bool(fresh) and fetched and not (title or "").strip() and not edits and not dirty and not saving and not read_only
