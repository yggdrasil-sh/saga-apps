"""KI-Darstellung (Bugs-DB 2248): aimd.lines gegen die Server-Vektoren (vectors25/ki_md.json, Kopie von
docs/ki-markdown-vektoren.json aus dem Repo saga) und das Pango-Markup des Assistenten."""
import json
import os

import pytest

from hlidskjalf import aimd

HERE = os.path.dirname(os.path.abspath(__file__))
VEC = json.load(open(os.path.join(HERE, "vectors25", "ki_md.json"), encoding="utf-8"))


@pytest.mark.parametrize("v", VEC["assistant"], ids=lambda v: v["in"][:30])
def test_vectors(v):
    assert aimd.lines(v["in"], set(VEC["cites"])) == v["lines"]


def test_markup():
    from hlidskjalf.assistant import answer_markup
    src = [{"n": 1, "id": 5, "title": "T"}, {"n": 2, "id": None, "title": "ohne Zugriff"}]
    m = answer_markup("## Plan <x>\n- **Kohle** [1]\n1. `a<b` [2]\n2. zwei", src)
    assert answer_markup("1. a\nText\n2. b", src) == "1.\u2002a\nText\n2.\u2002b"   # Nummer aus dem Text
    assert m == ('<b>Plan &lt;x&gt;</b>\n• <b>Kohle</b> <a href="src:1">[1]</a>\n1. <tt>a&lt;b</tt> [2]\n2. zwei')
