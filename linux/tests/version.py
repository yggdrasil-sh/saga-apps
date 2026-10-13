# Version: Server-Version aus /api/version, Anzeige in den Einstellungen, Hinweis "App zu alt"
S = {}


def s1(g):
    st = g["store"]
    print("Server-Version:", st.server_version, "min_app:", st.min_app, "zu alt:", st.app_too_old, flush=True)
    st.min_app = "99.0.0"                                       # Server verlangt eine neuere App -> Hinweis
    st.emit("version-changed")


def s2(g):
    w = g["win"]
    print("Hinweis sichtbar:", w.old_banner.get_revealed(), "|", w.old_banner.get_title(), flush=True)
    g["shot"](g["out"] + "/01-zu-alt.png")
    st = g["store"]
    st.min_app = "1.0.0"
    st.emit("version-changed")


def s3(g):
    print("Hinweis nach passender Version:", g["win"].old_banner.get_revealed(), flush=True)


steps = [(5000, s1), (1200, s2), (1000, s3)]
