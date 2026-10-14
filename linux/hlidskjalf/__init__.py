"""Saga – native Linux-App (GTK4 + libadwaita)."""
VERSION = "1.1.0"
APP_ID = "io.github.yggdrasil_sh.Saga"
PROJECT_URL = "https://github.com/yggdrasil-sh/saga-apps"


def version_tuple(v):
    """'1.2.3' / 'v1.2.3-beta' -> (1, 2, 3)"""
    out = []
    for part in str(v or "").lstrip("vV").split("-", 1)[0].split(".")[:3]:
        try:
            out.append(int(part))
        except ValueError:
            out.append(0)
    return tuple((out + [0, 0, 0])[:3])
