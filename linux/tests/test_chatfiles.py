"""4.11.: Chat-Anhaenge oeffnen - private Kopie und Rueckfrage-Entscheidung (reine Logik, ohne GTK)"""
import os
import stat

from hlidskjalf import chatfiles


def test_direct_open_documents_and_media():
    for m in ("application/pdf", "text/plain", "image/png", "audio/ogg", "video/mp4",
              "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/pdf; charset=binary"):
        assert chatfiles.direct_open(m), m


def test_ask_for_everything_else():
    for m in ("", "application/octet-stream", "application/x-desktop", "application/x-shellscript", "text/x-python",
              "application/x-executable", "application/zip", "image/svg+xml", "text/html"):
        assert not chatfiles.direct_open(m), m


def test_safe_name():
    assert chatfiles.safe_name("../../etc/passwd") == "-..-etc-passwd"
    assert chatfiles.safe_name("") == "datei"
    assert chatfiles.safe_name(".hidden") == "hidden"
    assert chatfiles.safe_name("Saga-Handbuch.pdf") == "Saga-Handbuch.pdf"


def test_private_path_and_remove(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))
    p = chatfiles.private_path("a/b.pdf")
    assert os.path.basename(p) == "a-b.pdf"
    d = os.path.dirname(p)
    assert stat.S_IMODE(os.stat(d).st_mode) == 0o700
    assert stat.S_IMODE(os.stat(chatfiles.base_dir()).st_mode) == 0o700
    open(p, "wb").write(b"x")
    chatfiles.remove(p)
    assert not os.path.exists(d)
    chatfiles.remove(str(tmp_path / "fremd" / "x"))          # ausserhalb: nichts passiert
