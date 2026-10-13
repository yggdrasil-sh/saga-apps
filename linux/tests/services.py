def go(g):
    g["win"].show_root(("services",))


def take(g):
    g["shot"](g["out"] + "/60-dienste.png")


steps = [(2500, go), (5000, take)]
