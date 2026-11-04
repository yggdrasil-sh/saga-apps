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


# --- Senden: v1 oder saga-file/v2 (4.11.) ----------------------------------------------------------------------------------
MiB = chatfiles.MiB


def test_v1_max_fed_capped():
    lim = {"file_max": 25 * MiB, "file2": True, "video_max": 500 * MiB}
    assert chatfiles.v1_max(lim) == 25 * MiB
    assert chatfiles.v1_max(lim, fed=True) == 10 * MiB                 # ueber Servergrenzen hoechstens 10 MiB
    assert chatfiles.v1_max({"file_max": 5 * MiB}, fed=True) == 5 * MiB
    assert chatfiles.v1_max({}) == 10 * MiB


def test_send_plan_local():
    lim = {"file_max": 25 * MiB, "file2": True, "video_max": 500 * MiB}
    p = chatfiles.send_plan
    assert p(0, lim) == ("empty", 0)
    assert p(1, lim) == ("v1", 0) and p(25 * MiB, lim) == ("v1", 0)
    assert p(25 * MiB + 1, lim) == ("v2", 0) and p(500 * MiB, lim) == ("v2", 0)
    assert p(500 * MiB + 1, lim) == ("max", 500)                        # ueber video_max: Hinweis
    assert p(25 * MiB + 1, {"file_max": 25 * MiB}) == ("server", 25)    # Server ohne file2: wie bisher
    assert chatfiles.max_send(lim) == 500 * MiB and chatfiles.max_send({"file_max": 25 * MiB}) == 25 * MiB


def test_send_plan_fed():
    lim = {"file_max": 25 * MiB, "file2": True, "video_max": 500 * MiB}
    p = chatfiles.send_plan
    assert p(10 * MiB, lim, True, True) == ("v1", 0)
    assert p(10 * MiB + 1, lim, True, True) == ("v2", 0)                # unter file_max, aber ueber 10 MiB: v2
    assert p(10 * MiB + 1, lim, True, None) == ("v2", 0)
    assert p(10 * MiB + 1, lim, True, False) == ("peer", 10)            # Gegenueber ohne chatfile2: nur v1 bis 10 MiB
    assert p(10 * MiB + 1, {"file_max": 25 * MiB}, True, True) == ("server", 10)
    assert p(10 * MiB + 1, lim, False, False) == ("v1", 0)              # big_files zaehlt nur ueber Servergrenzen
    assert chatfiles.max_send(lim, True, False) == 10 * MiB and chatfiles.max_send(lim, True, True) == 500 * MiB
