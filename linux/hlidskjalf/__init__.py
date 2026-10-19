"""Saga – native Linux-App (GTK4 + libadwaita)."""
VERSION = "2.3.0"
APP_ID = "io.github.yggdrasil_sh.Saga"
PROJECT_URL = "https://github.com/yggdrasil-sh/saga-apps"

_label = None


def version_label():
    """Angezeigte Version: VERSION im offiziellen Release, sonst z. B. '2.2.0-rc.3'.

    Pakete (RPM/Flatpak) bringen sie in _buildinfo.py mit (linux/stamp-version.sh); im Quellbaum fragt die App
    tools/version-label.sh (Git; ohne Tags 'x.y.z-rc.0'), sonst bleibt es bei VERSION. Vergleiche (min_app) laufen weiter ueber VERSION."""
    global _label
    if _label is None:
        try:
            from ._buildinfo import VERSION_LABEL as lbl
        except ImportError:
            lbl = ""
            import os
            import subprocess
            script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tools", "version-label.sh")
            if os.path.isfile(script):
                try:
                    lbl = subprocess.run(["sh", script], capture_output=True, text=True, timeout=5).stdout.strip()
                except (OSError, subprocess.SubprocessError):
                    lbl = ""
            lbl = lbl or VERSION    # installiert ohne _buildinfo.py (fremder Paketbau): wie bisher
        _label = lbl
    return _label


def version_tuple(v):
    """'1.2.3' / 'v1.2.3-beta' / '1.2.3+dev.4' -> (1, 2, 3)"""
    out = []
    for part in str(v or "").lstrip("vV").split("-", 1)[0].split("+", 1)[0].split(".")[:3]:
        try:
            out.append(int(part))
        except ValueError:
            out.append(0)
    return tuple((out + [0, 0, 0])[:3])
