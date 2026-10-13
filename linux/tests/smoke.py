# Rauchtest: Fenster startet, Anmeldung bzw. Startseite, Bildschirmfoto
def s1(g):
    win = g["win"]
    print("Ansicht:", win.stack.get_visible_child_name(), "Seiten:", len(g["store"].pages), flush=True)
    g["shot"](g["out"] + "/01-start.png")


steps = [(2500, s1)]
