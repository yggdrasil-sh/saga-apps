#!/bin/sh
# RPM bauen (auf einem Rechner mit rpm-build, z. B. AlmaLinux/Fedora). Ergebnis: ~/rpmbuild/RPMS/noarch/
set -e
cd "$(dirname "$0")"
V=$(python3 -c 'import re;print(re.search(r"VERSION = \"([^\"]+)\"", open("hlidskjalf/__init__.py").read()).group(1))')
R=${HK_RELEASE:-1}
T=$(mktemp -d)
mkdir -p "$T/hlidskjalf-$V"
cp -a hlidskjalf data "$T/hlidskjalf-$V/"
find "$T" -name __pycache__ -prune -exec rm -rf {} +
mkdir -p ~/rpmbuild/SOURCES ~/rpmbuild/SPECS
tar -C "$T" -czf ~/rpmbuild/SOURCES/hlidskjalf-$V.tar.gz "hlidskjalf-$V"
rm -rf "$T"
rpmbuild -bb --define "hk_version $V" --define "hk_release $R" hlidskjalf.spec
ls -1 ~/rpmbuild/RPMS/noarch/hlidskjalf-$V-$R*.rpm
