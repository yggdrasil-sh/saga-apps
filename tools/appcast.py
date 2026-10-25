#!/usr/bin/env python3
# Erzeugt den Sparkle-Appcast (Selbst-Update der Mac-App) fuer EINE DMG - nur Standardbibliothek.
# Ein Kanal = eine Datei mit genau einem Eintrag (der neuesten Version):
#   appcast.xml     stabile Releases (Label x.y.z)        - gelesen von stabilen Builds
#   appcast-rc.xml  Vorabversionen (Label x.y.z-rc.N)    - gelesen von Testversionen
# Die Datei liegt im Zweig "appcast" (raw.githubusercontent.com/<repo>/appcast/<datei>); die CI ersetzt sie bei
# jeder Veroeffentlichung. Signiert wird danach mit Sparkles sign_update (DMG: --ed-key-file - -p, Appcast: Datei).
#
#   python3 tools/appcast.py --label 2.5.0-rc.74 --build 202610251845 --dmg ios/dist/Saga-2.5.0-rc.74-macOS.dmg \
#       --signature <EdDSA der DMG> --repo yggdrasil-sh/saga-apps --changelog CHANGELOG.md --out-dir ios/dist/appcast
# Ausgabe: Pfad der geschriebenen Datei.
import argparse
import email.utils
import os
import re
import sys
from xml.sax.saxutils import escape, quoteattr

LABEL_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(-rc\.[0-9]+)?$")
MIN_MACOS = "26.0.0"           # wie deploymentTarget macOS in ios/project.yml
MAX_NOTES = 12000              # Zeichen; laengere Notizen werden gekuerzt (Link zum Release folgt)


def feed_name(label):
    return "appcast-rc.xml" if "-rc." in label else "appcast.xml"


def notes(changelog, label):
    """Abschnitt aus CHANGELOG.md: [x.y.z] fuer Releases, [Unveröffentlicht] fuer Vorabversionen."""
    try:
        with open(changelog, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except OSError:
        return ""
    want = "[Unveröffentlicht]" if "-rc." in label else "[%s]" % label
    out, on = [], False
    for ln in lines:
        if ln.startswith("## ["):
            on = want in ln
            continue
        if on:
            out.append(ln)
    text = "\n".join(out).strip()
    if len(text) > MAX_NOTES:
        text = text[:MAX_NOTES].rsplit("\n", 1)[0] + "\n\n…"
    return text


def cdata(text):
    # "]]>" im Text wuerde den CDATA-Block beenden
    return "<![CDATA[" + text.replace("]]>", "]]]]><![CDATA[>") + "]]>"


def build(label, build_no, url, length, signature, page, notes_md, pub_date):
    rc = "-rc." in label
    title = "Saga %s%s" % (label, " (Testversion)" if rc else "")
    if not notes_md:
        notes_md = "Testversion vom Zweig testing." if rc else "Neue Version."
    notes_md += "\n\n[Alle Änderungen und Downloads](%s)" % page
    return "\n".join([
        '<?xml version="1.0" encoding="utf-8"?>',
        '<rss version="2.0" xmlns:sparkle="http://www.andymatuschak.org/xml-namespaces/sparkle" xmlns:dc="http://purl.org/dc/elements/1.1/">',
        "  <channel>",
        "    <title>%s</title>" % escape("Saga für macOS" + (" – Testversionen" if rc else "")),
        "    <link>%s</link>" % escape(page),
        "    <language>de</language>",
        "    <item>",
        "      <title>%s</title>" % escape(title),
        "      <link>%s</link>" % escape(page),
        "      <sparkle:version>%s</sparkle:version>" % escape(build_no),
        "      <sparkle:shortVersionString>%s</sparkle:shortVersionString>" % escape(label),
        "      <sparkle:minimumSystemVersion>%s</sparkle:minimumSystemVersion>" % MIN_MACOS,
        "      <sparkle:fullReleaseNotesLink>%s</sparkle:fullReleaseNotesLink>" % escape(page),
        "      <pubDate>%s</pubDate>" % pub_date,
        '      <description sparkle:format="markdown">%s</description>' % cdata(notes_md),
        "      <enclosure url=%s length=%s type=\"application/octet-stream\" sparkle:edSignature=%s/>"
        % (quoteattr(url), quoteattr(str(length)), quoteattr(signature)),
        "    </item>",
        "  </channel>",
        "</rss>",
        "",
    ])


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--label", required=True)
    ap.add_argument("--build", required=True, help="CFBundleVersion der App (sparkle:version)")
    ap.add_argument("--dmg", required=True)
    ap.add_argument("--signature", required=True, help="EdDSA-Signatur der DMG (base64, sign_update -p)")
    ap.add_argument("--repo", required=True, help="owner/name auf GitHub")
    ap.add_argument("--changelog", default="CHANGELOG.md")
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--date", help="pubDate (RFC 2822), Vorgabe: jetzt")
    a = ap.parse_args(argv)

    if not LABEL_RE.match(a.label):
        sys.exit("FEHLER: Label %r ist weder x.y.z noch x.y.z-rc.N" % a.label)
    if not re.fullmatch(r"[0-9]+(\.[0-9]+)*", a.build):
        sys.exit("FEHLER: Build-Nummer %r ist nicht numerisch" % a.build)
    if not re.fullmatch(r"[A-Za-z0-9+/]{86}==", a.signature.strip()):
        sys.exit("FEHLER: EdDSA-Signatur hat nicht das erwartete Format (64 Byte base64)")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", a.repo):
        sys.exit("FEHLER: Repo %r" % a.repo)
    name = os.path.basename(a.dmg)
    if name != "Saga-%s-macOS.dmg" % a.label:
        sys.exit("FEHLER: DMG %s passt nicht zum Label %s" % (name, a.label))
    length = os.path.getsize(a.dmg)

    base = "https://github.com/%s/releases" % a.repo
    url = "%s/download/v%s/%s" % (base, a.label, name)
    page = "%s/tag/v%s" % (base, a.label)
    xml = build(a.label, a.build, url, length, a.signature.strip(), page, notes(a.changelog, a.label),
                a.date or email.utils.formatdate(usegmt=True))
    os.makedirs(a.out_dir, exist_ok=True)
    out = os.path.join(a.out_dir, feed_name(a.label))
    with open(out, "w", encoding="utf-8") as f:
        f.write(xml)
    print(out)


if __name__ == "__main__":
    main()
