"""Leere Zustaende mit naechstem Schritt (2.6, Bugs-DB 2031): ein kurzer Satz, was hier erscheint, und EIN klarer Knopf
zum naechsten Schritt (bzw. zur fehlenden Voraussetzung, z. B. "Kalender verbinden"). Wie HK.empty im Web."""
from gi.repository import Gtk


def empty_state(text, label=None, cb=None, need=False):
    """Box mit Satz (umbrechend, gedaempft) und optional einem Knopf (Pille, >= 44 px hoch, hervorgehoben).
    need=True: der Knopf fuehrt zu einer fehlenden Voraussetzung (Klasse hk-empty-need, nicht hervorgehoben)."""
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_top=6, margin_bottom=6)
    box.add_css_class("hk-emptystate")
    lb = Gtk.Label(label=text, xalign=0, wrap=True, natural_wrap_mode=Gtk.NaturalWrapMode.WORD)
    lb.add_css_class("dim-label")
    box.append(lb)
    box.text_label = lb
    box.button = None
    if label and cb:
        b = Gtk.Button(label=label, halign=Gtk.Align.START)
        if not need:                                   # GNOME HIG: eine hervorgehobene Aktion je Ansicht - die Voraussetzung
            b.add_css_class("suggested-action")        # (z. B. "Kalender verbinden") steht daneben ruhiger
        b.add_css_class("pill")
        b.add_css_class("hk-empty-go")
        if need:
            b.add_css_class("hk-empty-need")
        # Satz als Beschreibung des Knopfs: der Screenreader liest beim Fokus, worum es geht
        b.update_property([Gtk.AccessibleProperty.DESCRIPTION], [text])
        b.connect("clicked", lambda *_: cb())
        box.append(b)
        box.button = b
    return box
