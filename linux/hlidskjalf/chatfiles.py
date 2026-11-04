"""Chat-Anhaenge oeffnen (4.11.): kurzlebige, private Klartext-Kopie und die Entscheidung, ob das Standardprogramm ohne
Rueckfrage starten darf. Ohne GTK, damit die Logik testbar bleibt."""
import os
import re
import secrets
import shutil
import tempfile

KEEP_S = 600                     # so lange bleibt die entschluesselte Kopie liegen (das Programm hat sie dann geoeffnet)

# Dokumente und Medien: direkt mit dem Standardprogramm. Alles andere (Skripte, .desktop, Programme, Archive …) nur mit
# Rueckfrage "Oeffnen mit" - ein Anhang aus dem Chat soll nie still etwas ausfuehren.
_DIRECT = {
    "application/pdf", "text/plain", "text/markdown", "text/csv",
    "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
    "application/vnd.oasis.opendocument.presentation",
    "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
}
_DIRECT_PREFIX = ("image/", "audio/", "video/")
_UNSAFE_IMAGE = {"image/svg+xml"}


def direct_open(mime):
    """Darf der Anhang ohne Rueckfrage mit dem Standardprogramm geoeffnet werden?"""
    m = (mime or "").split(";", 1)[0].strip().lower()
    if m in _UNSAFE_IMAGE:
        return False
    return m in _DIRECT or m.startswith(_DIRECT_PREFIX)


def safe_name(name):
    """Dateiname ohne Pfadteile/Steuerzeichen, nie leer, nie versteckt"""
    n = re.sub(r"[/\\\x00-\x1f]", "-", name or "").strip(". ")
    return n[:200] or "datei"


def base_dir():
    """Privater Ordner fuer Klartext-Kopien: $XDG_RUNTIME_DIR (tmpfs, nur fuer mich), sonst das Temp-Verzeichnis"""
    root = os.environ.get("XDG_RUNTIME_DIR") or tempfile.gettempdir()
    d = os.path.join(root, "saga-chat")
    os.makedirs(d, mode=0o700, exist_ok=True)
    try:
        os.chmod(d, 0o700)
    except OSError:
        pass
    return d


def private_path(name):
    """Neuer Zufallsordner (0700) je Anhang, darin der Originalname - das Programm zeigt den richtigen Namen"""
    d = os.path.join(base_dir(), secrets.token_hex(8))
    os.mkdir(d, 0o700)
    return os.path.join(d, safe_name(name))


def remove(path):
    """Kopie samt Zufallsordner loeschen (nur innerhalb von base_dir)"""
    d = os.path.dirname(path)
    if os.path.dirname(d) == base_dir():
        shutil.rmtree(d, ignore_errors=True)


# --- Senden: v1 oder saga-file/v2 (4.11.) ----------------------------------------------------------------------------------
MiB = 1048576
FED_V1_MAX = 10 * MiB            # ueber Servergrenzen hoechstens so gross als v1 (ein Block), groesser nur als v2


def v1_max(limits, fed=False):
    """groesste Klartext-Groesse fuer v1 (ein Block): file_max, in Chats ueber Servergrenzen hoechstens 10 MiB"""
    fmax = int((limits or {}).get("file_max") or FED_V1_MAX)
    return min(fmax, FED_V1_MAX) if fed else fmax


def send_plan(size, limits, fed=False, big_files=None):
    """Wie wird ein Anhang (oder Video) dieser Groesse gesendet? -> (art, n_mb)
    art: "v1" | "v2" | "empty" | "server" (Server ohne limits.file2) | "peer" (Gegenueber ohne chatfile2) | "max"
    (groesser als video_max). n_mb: die fuer den Hinweis passende Grenze in MB (sonst 0)."""
    lim = limits or {}
    size = int(size or 0)
    if size <= 0:
        return "empty", 0
    v1 = v1_max(lim, fed)
    if size <= v1:
        return "v1", 0
    n1 = max(1, v1 // MiB)
    if not lim.get("file2"):
        return "server", n1
    if fed and big_files is False:
        return "peer", n1
    vmax = int(lim.get("video_max") or 0)
    if vmax and size > vmax:
        return "max", max(1, vmax // MiB)
    return "v2", 0


def max_send(limits, fed=False, big_files=None):
    """groesste sendbare Klartext-Groesse (v1 oder v2)"""
    lim = limits or {}
    v1 = v1_max(lim, fed)
    if not lim.get("file2") or (fed and big_files is False):
        return v1
    return max(v1, int(lim.get("video_max") or 0) or v1)
