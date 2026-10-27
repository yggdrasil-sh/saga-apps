"""Eintraege anlegen (Saga 2.5, Fix-Runde 27.10.) - reine Logik ohne GTK (tests/test_fix27.py).

- #11 Lesen/Kommentieren: Anlege-Schaltflaechen bleiben sichtbar, aber ausgegraut mit Hinweis
  (Schaltflaechen-Block "Neuer Eintrag", "Neu" der Datenbank)."""
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
