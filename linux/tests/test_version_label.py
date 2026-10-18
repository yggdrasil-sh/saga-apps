"""Angezeigte Version (Release Candidate): stamp-version.sh schreibt _buildinfo.py + Metainfo, version_tuple ignoriert Zusaetze."""
import os
import shutil
import subprocess

import hlidskjalf
from hlidskjalf import VERSION, version_tuple

LINUX = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def test_version_tuple_mit_zusatz():
    assert version_tuple("2.2.0-rc.3") == (2, 2, 0)
    assert version_tuple("2.2.0+dev.4") == (2, 2, 0)
    assert version_tuple("v2.1.0") == (2, 1, 0)


def test_label_beginnt_mit_version():
    hlidskjalf._label = None
    assert hlidskjalf.version_label().startswith(VERSION)


def _kopie(tmp_path):
    for d in ("hlidskjalf", "data"):
        shutil.copytree(os.path.join(LINUX, d), tmp_path / d, ignore=shutil.ignore_patterns("__pycache__", "_buildinfo.py"))
    return tmp_path


def _stamp(d, *args):
    return subprocess.run(["sh", os.path.join(LINUX, "stamp-version.sh"), str(d), *args], capture_output=True, text=True)


def test_stamp_rc(tmp_path):
    d = _kopie(tmp_path)
    lbl = VERSION + "-rc.7"
    assert _stamp(d, lbl, VERSION + "~rc7").returncode == 0
    assert f'VERSION_LABEL = "{lbl}"' in (d / "hlidskjalf" / "_buildinfo.py").read_text()
    meta = (d / "data" / "io.github.yggdrasil_sh.Saga.metainfo.xml").read_text()
    assert f'<release version="{VERSION}~rc7"' in meta and 'type="development"' in meta


def test_stamp_release_ohne_metainfo_eintrag(tmp_path):
    d = _kopie(tmp_path)
    before = (d / "data" / "io.github.yggdrasil_sh.Saga.metainfo.xml").read_text()
    assert _stamp(d, VERSION, VERSION).returncode == 0
    assert f'VERSION_LABEL = "{VERSION}"' in (d / "hlidskjalf" / "_buildinfo.py").read_text()
    assert (d / "data" / "io.github.yggdrasil_sh.Saga.metainfo.xml").read_text() == before


def test_stamp_falsches_label(tmp_path):
    d = _kopie(tmp_path)
    assert _stamp(d, "0.0.1-rc.1").returncode == 1
    assert not (d / "hlidskjalf" / "_buildinfo.py").exists()
