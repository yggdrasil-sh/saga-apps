#!/usr/bin/env python3
"""Update-Info fuer den Update-Hinweis in den Apps (Einstellungen -> Info).

Schreibt <out-dir>/<kanal>.json (stable.json bzw. testing.json) mit der neuesten Version des Kanals und den
Download-Links. Die CI legt die Datei nach dem Release in den Zweig "appcast" (tools/publish-appcast.sh); die Apps lesen
https://raw.githubusercontent.com/<repo>/appcast/<kanal>.json und vergleichen mit der eigenen Version - nur im eigenen
Kanal (Vorabversion x.y.z-rc.N -> testing, sonst stable).

  python3 tools/update-info.py --label 2.5.0-rc.136 --repo owner/saga-apps --dist dist --commit <sha> --out-dir out

Format (version 1):
  {"format": 1, "channel": "testing", "version": "2.5.0-rc.136", "tag": "v2.5.0-rc.136", "commit": "abc1234",
   "published": "2026-10-26T06:00:00Z", "page": "https://github.com/<repo>/releases/tag/v2.5.0-rc.136",
   "assets": {"android": "…apk", "macos": "…dmg", "rpm": "…rpm", "flatpak-x86_64": "…", "flatpak-aarch64": "…"}}
Fehlt ein Asset im Release, fehlt sein Schluessel - die App zeigt dann nur den Hinweis bzw. die Release-Seite.
"""
import argparse
import datetime
import json
import os
import re
import sys

LABEL = re.compile(r"^\d+\.\d+\.\d+(-rc\.\d+)?$")
REPO = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
KINDS = [  # (Schluessel, Muster des Dateinamens)
    ("android", re.compile(r"^Saga-.+\.apk$")),
    ("macos", re.compile(r"^Saga-.+-macOS\.dmg$")),
    ("rpm", re.compile(r"^saga-.+\.noarch\.rpm$")),
    ("flatpak-x86_64", re.compile(r"^saga-.+x86_64\.flatpak$")),
    ("flatpak-aarch64", re.compile(r"^saga-.+aarch64\.flatpak$")),
]


def build(label, repo, files, commit, now=None):
    if not LABEL.match(label):
        raise ValueError(f"unerwartetes Label {label!r}")
    if not REPO.match(repo):
        raise ValueError(f"unerwartetes Repo {repo!r}")
    tag = "v" + label
    base = f"https://github.com/{repo}/releases"
    assets = {}
    for key, pat in KINDS:
        hits = sorted(f for f in files if pat.match(f))
        if hits:
            assets[key] = f"{base}/download/{tag}/{hits[0]}"
    now = now or datetime.datetime.now(datetime.timezone.utc)
    return {
        "format": 1,
        "channel": "testing" if "-rc." in label else "stable",
        "version": label,
        "tag": tag,
        "commit": (commit or "")[:7],
        "published": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "page": f"{base}/tag/{tag}",
        "assets": assets,
    }


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument("--label", required=True)
    p.add_argument("--repo", required=True)
    p.add_argument("--dist", required=True)
    p.add_argument("--commit", default="")
    p.add_argument("--out-dir", required=True)
    a = p.parse_args(argv)
    info = build(a.label, a.repo, os.listdir(a.dist), a.commit)
    os.makedirs(a.out_dir, exist_ok=True)
    out = os.path.join(a.out_dir, info["channel"] + ".json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(info, f, indent=1, sort_keys=True)
        f.write("\n")
    print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
