"""Paket-Abhaengigkeiten im RPM-Spec (BUG-006, BUG-026). Der eigentliche Nachweis ist die Installation in frischen
Containern (fedora:44, almalinux:10 + EPEL); hier nur, dass die Zeilen nicht wieder verschwinden."""
import os
import re

SPEC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "hlidskjalf.spec")


def requires():
    out = []
    with open(SPEC, encoding="utf-8") as f:
        for line in f:
            m = re.match(r"^Requires:\s*(.+?)\s*$", line)
            if m:
                out.append(m.group(1))
    return out


def test_gobject_introspection_required():
    # cairo-1.0.typelib (von Gtk-4.0 verlangt) liegt nur in gobject-introspection
    assert "gobject-introspection" in requires()


def test_pynacl_lower_bound_fits_epel10():
    # EPEL 10 liefert python3-pynacl 1.5.0 - eine hoehere Untergrenze macht das RPM dort uninstallierbar
    req = [r for r in requires() if r.startswith("python3dist(pynacl)")]
    assert len(req) == 1
    m = re.search(r">=\s*([0-9.]+)", req[0])
    if m:
        assert tuple(int(x) for x in m.group(1).split(".")) <= (1, 5)


def test_chatcrypto_only_uses_pynacl_15_api():
    # Funktionen, die PyNaCl 1.5.0 kennt (gegen die Testvektoren mit 1.5.0 geprueft); neue Aufrufe hier eintragen
    # und in almalinux:10 + EPEL pruefen
    known = {"crypto_sign_keypair", "crypto_sign_seed_keypair", "crypto_sign", "crypto_sign_open",
             "crypto_box_keypair", "crypto_box_seed_keypair", "crypto_box_seal", "crypto_box_seal_open",
             "crypto_scalarmult_base", "crypto_aead_xchacha20poly1305_ietf_encrypt",
             "crypto_aead_xchacha20poly1305_ietf_decrypt", "crypto_aead_xchacha20poly1305_ietf_KEYBYTES"}
    with open(os.path.join(os.path.dirname(SPEC), "hlidskjalf", "chatcrypto.py"), encoding="utf-8") as f:
        used = set(re.findall(r"\bnb\.(\w+)", f.read()))
    assert used <= known, sorted(used - known)
    try:
        import nacl.bindings as nb
    except ImportError:
        return
    assert [n for n in used if not hasattr(nb, n)] == []
