# Seitenleiste ein-/ausklappen: Aktion (F9), Knopf in der Kopfleiste, gemerkter Zustand, schmales Fenster
S = {}


def zustand(g, was):
    w = g["win"]
    from hlidskjalf.config import settings
    print(was, "| collapsed:", w.split.get_collapsed(), "show-sidebar:", w.split.get_show_sidebar(),
          "knopf aktiv:", w.sb_btn.get_active(), "knopf sichtbar:", w.sb_btn.get_mapped(),
          "gemerkt:", settings.get("sidebar_shown"), "| breite:", w.get_width(), flush=True)


def s1(g):
    w = g["win"]
    from hlidskjalf.config import settings
    S["orig"] = settings.get("sidebar_shown")
    S["size"] = w.get_default_size()
    S["max"] = w.is_maximized()
    print("Split-Typ:", type(w.split).__name__, "max:", S["max"], "size:", S["size"], flush=True)
    if S["max"]:
        w.unmaximize()
    w.split.set_show_sidebar(True)                              # definierter Start: ausgeklappt
    zustand(g, "Start")
    g["shot"](g["out"] + "/01-start.png")
    w.activate_action("win.toggle-sidebar", None)               # wie F9


def s2(g):
    zustand(g, "Nach F9-Aktion (erwartet show-sidebar False, gemerkt False)")
    g["shot"](g["out"] + "/02-eingeklappt.png")
    g["win"].set_default_size(600, 800)                         # schmal -> Breakpoint


def s3(g):
    zustand(g, "Schmal (erwartet collapsed True, show-sidebar False)")
    g["shot"](g["out"] + "/03-schmal.png")
    g["win"].activate_action("win.toggle-sidebar", None)


def s4(g):
    zustand(g, "Schmal + F9 (erwartet show-sidebar True als Overlay, gemerkt weiter False)")
    g["shot"](g["out"] + "/04-schmal-overlay.png")
    g["win"].show_root(("tasks",))                              # Auswahl in der Seitenleiste


def s5(g):
    zustand(g, "Schmal nach Auswahl (erwartet show-sidebar False)")
    g["win"].set_default_size(1280, 820)


def s6(g):
    zustand(g, "Wieder breit (erwartet collapsed False, show-sidebar False wie gemerkt)")
    g["shot"](g["out"] + "/05-breit-eingeklappt.png")
    g["win"].sb_btn.emit("clicked")                             # Knopf in der Kopfleiste


def s7(g):
    zustand(g, "Nach Knopf (erwartet show-sidebar True, gemerkt True)")
    g["shot"](g["out"] + "/06-ausgeklappt.png")
    w = g["win"]
    from hlidskjalf.config import settings
    settings.set("sidebar_shown", S["orig"])                    # Einstellungen des Test-Laptops nicht veraendern
    w.set_default_size(*S["size"])
    if S["max"]:
        w.maximize()


steps = [(5000, s1), (800, s2), (1500, s3), (1500, s4), (800, s5), (1500, s6), (800, s7), (500, lambda g: None)]
