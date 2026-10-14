"""Entwuerfe auf der Platte - je Server UND Konto getrennt (wie Android):
    ~/.local/share/hlidskjalf/drafts/<kennung>/<id>.json             ungespeicherter Stand (offline, Absturz, Schliessen)
    ~/.local/share/hlidskjalf/drafts/<kennung>/<id>.copy-<ms>.json   lokale Kopie der eigenen Fassung (Konflikt:
                                                                     "Server-Fassung laden"), Banner bietet sie an
<kennung> = sha256("<server>|<konto>")[:16]. Solange das Konto unbekannt ist (erster Start nach dem Update, kurz nach
einer Anmeldung), gilt sha256("<server>")[:16] - so lagen Entwuerfe frueher; sie werden dem Konto zugeordnet, sobald
es bekannt ist (migrate). Ein anderes Konto am selben Server sieht fremde Entwuerfe nie."""
import hashlib
import json
import os
import re
import time

from . import config

DIR = os.path.join(config.DATA_DIR, "drafts")
CURRENT = object()              # Standard: das gerade angemeldete Konto am Server


def bucket(server=None, account=CURRENT):
    srv = config.origin(server or config.settings.server) or "-"
    if account is CURRENT:
        account = config.account(srv)
    key = f"{srv}|{account}" if account else srv
    return os.path.join(DIR, hashlib.sha256(key.encode()).hexdigest()[:16])


def path(pid, server=None, account=CURRENT):
    return os.path.join(bucket(server, account), f"{pid}.json")


def read(pid, server=None, account=CURRENT):
    try:
        with open(path(pid, server, account), encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) and d.get("id") == pid else None
    except (OSError, ValueError):
        return None


def write(d, server=None, account=CURRENT):
    """-> True, wenn sicher auf der Platte"""
    try:
        p = path(d["id"], server, account)
        os.makedirs(os.path.dirname(p), mode=0o700, exist_ok=True)
        config.atomic_write(p, json.dumps(d, ensure_ascii=False))
        return True
    except (OSError, TypeError, ValueError):
        return False


def drop(pid, server=None, account=CURRENT):
    try:
        os.remove(path(pid, server, account))
    except OSError:
        pass


# --- lokale Kopien (Konflikt: eigene Fassung, bevor die Server-Fassung geladen wird) ---------------------------------
def write_copy(d, server=None, account=CURRENT):
    """eigene Fassung als lokale Kopie ablegen (mehrere je Seite moeglich, keine ueberschreibt eine andere).
    -> Pfad oder None (nicht geschrieben)"""
    b = bucket(server, account)
    ms = int(time.time() * 1000)
    try:
        os.makedirs(b, mode=0o700, exist_ok=True)
        while os.path.exists(os.path.join(b, f"{d['id']}.copy-{ms}.json")):
            ms += 1
        p = os.path.join(b, f"{d['id']}.copy-{ms}.json")
        config.atomic_write(p, json.dumps(dict(d, time=ms / 1000), ensure_ascii=False))
        return p
    except (OSError, TypeError, ValueError):
        return None


def copies(pid, server=None, account=CURRENT):
    """lokale Kopien einer Seite, neueste zuerst: [(pfad, entwurf), ...]"""
    b = bucket(server, account)
    rx = re.compile(rf"^{int(pid)}\.copy-(\d+)\.json$")
    out = []
    try:
        names = os.listdir(b)
    except OSError:
        return []
    for n in names:
        m = rx.match(n)
        if not m:
            continue
        try:
            with open(os.path.join(b, n), encoding="utf-8") as f:
                d = json.load(f)
        except (OSError, ValueError):
            continue
        if isinstance(d, dict) and d.get("id") == pid:
            out.append((int(m.group(1)), os.path.join(b, n), d))
    out.sort(key=lambda x: -x[0])
    return [(p, d) for _, p, d in out]


def drop_copy(p):
    try:
        os.remove(p)
    except OSError:
        pass


# --- Uebernahme: Entwuerfe ohne Konto (frueheres Format) -> Konto ----------------------------------------------------
def migrate(server, account):
    """Entwuerfe, die ohne bekanntes Konto abgelegt wurden (vor dem Update oder kurz nach der Anmeldung), gehoeren
    dem Konto, das jetzt an diesem Server angemeldet ist. Vorhandenes wird nie ueberschrieben."""
    if not account:
        return 0
    old, new = bucket(server, None), bucket(server, account)
    try:
        names = os.listdir(old)
    except OSError:
        return 0
    n = 0
    for name in names:
        if not name.endswith(".json") or name.startswith("."):
            continue
        os.makedirs(new, mode=0o700, exist_ok=True)
        src, dst = os.path.join(old, name), os.path.join(new, name)
        if os.path.exists(dst):
            continue
        try:
            os.replace(src, dst)
            n += 1
        except OSError:
            pass
    try:
        os.rmdir(old)
    except OSError:
        pass
    return n
