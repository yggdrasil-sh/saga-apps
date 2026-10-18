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


def test_stamp_naechste_patch_version(tmp_path):
    # nach einem Release ohne Versionssprung: Label 2.1.1-rc.N bei VERSION 2.1.0 ist erlaubt
    d = _kopie(tmp_path)
    nxt = "%d.%d.%d" % (version_tuple(VERSION)[0], version_tuple(VERSION)[1], version_tuple(VERSION)[2] + 1)
    assert _stamp(d, nxt + "-rc.2", nxt + "~rc2").returncode == 0
    assert f'VERSION_LABEL = "{nxt}-rc.2"' in (d / "hlidskjalf" / "_buildinfo.py").read_text()


# tools/version-label.sh in einem Wegwerf-Repo: Tags, Commits, Release-Schalter
ROOT = os.path.dirname(LINUX)


def _repo(tmp_path, version):
    r = tmp_path / "repo"
    (r / "tools").mkdir(parents=True)
    shutil.copy(os.path.join(ROOT, "tools", "version-label.sh"), r / "tools" / "version-label.sh")
    (r / "VERSION").write_text(version + "\n")
    env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@t", GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@t")
    for k in ("SAGA_VERSION_LABEL", "SAGA_RELEASE"):
        env.pop(k, None)

    def git(*a):
        subprocess.run(["git", "-C", str(r), *a], check=True, capture_output=True, env=env)

    git("init", "-q")
    git("add", "-A")
    git("commit", "-qm", "start")
    return r, git, env


def _label(r, env, *args, **extra):
    p = subprocess.run(["sh", str(r / "tools" / "version-label.sh"), *args], capture_output=True, text=True,
                       env=dict(env, **extra))
    return p.returncode, p.stdout.strip()


def test_label_ohne_tags(tmp_path):
    r, git, env = _repo(tmp_path, "2.2.0")
    assert _label(r, env) == (0, "2.2.0-rc.0")


def test_label_rc_und_release(tmp_path):
    r, git, env = _repo(tmp_path, "2.1.0")
    git("tag", "v2.1.0")
    assert _label(r, env) == (0, "2.1.0")                       # genau der Release-Commit
    (r / "VERSION").write_text("2.2.0\n")
    git("commit", "-qam", "naechste")
    for i in range(2):
        git("commit", "-q", "--allow-empty", "-m", f"c{i}")
    git("tag", "v2.2.0-rc.1")                                    # Vorab-Tags zaehlen nicht als Release
    assert _label(r, env) == (0, "2.2.0-rc.3")
    assert _label(r, env, "--rpm-release") == (0, "0.rc3")
    assert _label(r, env, "--appstream") == (0, "2.2.0~rc3")
    assert _label(r, env, "--base") == (0, "2.2.0")
    assert _label(r, env, SAGA_RELEASE="1") == (0, "2.2.0")
    assert _label(r, env, "--rpm-release", SAGA_RELEASE="1") == (0, "1")


def test_label_nach_release_naechste_patch_version(tmp_path):
    # wie der Server: steht VERSION noch auf dem gerade veroeffentlichten Release, zaehlt der RC zur naechsten Patch-Version
    r, git, env = _repo(tmp_path, "2.1.0")
    git("tag", "v2.1.0")
    for i in range(10):
        git("commit", "-q", "--allow-empty", "-m", f"c{i}")
    assert _label(r, env) == (0, "2.1.1-rc.10")
    assert _label(r, env, "--base") == (0, "2.1.1")
    assert _label(r, env, "--rpm-release") == (0, "0.rc10")
    assert _label(r, env, "--appstream") == (0, "2.1.1~rc10")


def test_label_vorgabe_wird_geprueft(tmp_path):
    r, git, env = _repo(tmp_path, "2.1.0")
    assert _label(r, env, SAGA_VERSION_LABEL="2.1.0-rc.4") == (0, "2.1.0-rc.4")
    assert _label(r, env, SAGA_VERSION_LABEL="2.1.1-rc.4") == (0, "2.1.1-rc.4")
    assert _label(r, env, SAGA_VERSION_LABEL="2.1.0") == (0, "2.1.0")
    assert _label(r, env, SAGA_VERSION_LABEL="2.0.9-rc.4")[0] != 0
    assert _label(r, env, SAGA_VERSION_LABEL="2.1.0-testing")[0] != 0
    assert _label(r, env, SAGA_VERSION_LABEL="2.1.0-rc.x")[0] != 0
