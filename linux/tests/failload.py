# Seite, die nicht geladen werden kann: keine leere, bearbeitbare Ansicht und vor allem KEIN Speichern
S = {}


def s1(g):
    g["win"].show_root(("page", 987654321))       # gibt es nicht -> Fehler beim Laden


def s2(g):
    v = g["win"].current
    d = v.doc
    print("geladen:", d.loaded, "Daten:", d.fetched, "Fehler:", bool(d.error), flush=True)
    print("Ansicht:", type(v.scroll.get_child()).__name__, flush=True)
    d.mark_dirty()
    d.save()
    print("speichert trotzdem:", d._saving, flush=True)
    g["shot"](g["out"] + "/01-fehler.png")


steps = [(4000, s1), (3000, s2)]
